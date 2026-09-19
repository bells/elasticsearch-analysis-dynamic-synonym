package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import static com.bellszhu.elasticsearch.plugin.synonym.analysis.SynonymTestSupport.*;
import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class SnapshotFilterTest {
    @Parameterized.Parameters(name = "graph={0}")
    public static Object[] variants() { return new Object[] {false, true}; }
    @Parameterized.Parameter public boolean graph;

    @Test
    public void emptyNonEmptyAndEmptyAgainOnReusedStream() throws Exception {
        AtomicReference<SynonymMap> maps = new AtomicReference<>(map(""));
        try (Analyzer analyzer = analyzer(graph, maps::get)) {
            assertEquals(List.of("a"), terms(analyzer, "a"));
            maps.set(map("a => b c"));
            assertEquals(List.of("b", "c"), terms(analyzer, "a"));
            maps.set(map(""));
            assertEquals(List.of("a"), terms(analyzer, "a"));
            maps.set(map("a => d"));
            assertEquals(List.of("d"), terms(analyzer, "a"));
        }
    }

    @Test
    public void activeStreamPinsMapUntilNextReset() throws Exception {
        AtomicReference<SynonymMap> maps = new AtomicReference<>(map("a => old"));
        try (Analyzer analyzer = analyzer(graph, maps::get)) {
            try (TokenStream stream = analyzer.tokenStream("field", "a a a")) {
                stream.reset();
                assertTrue(stream.incrementToken());
                maps.set(map("a => new longer"));
                assertEquals(List.of("old", "old"), drain(stream).stream().map(Token::term).toList());
                stream.end();
            }
            assertEquals(List.of("new", "longer"), terms(analyzer, "a"));
        }
    }

    @Test
    public void multiWordPositionsAndOffsets() throws Exception {
        SynonymMap rules = map("new york => nyc");
        try (Analyzer analyzer = analyzer(graph, () -> rules)) {
            List<Token> result = tokens(analyzer, "new york city");
            assertEquals(List.of("nyc", "city"), result.stream().map(Token::term).toList());
            assertEquals(new Token("nyc", "SYNONYM", 1, 1, 0, 8), result.get(0));
            assertEquals(new Token("city", "word", 1, 1, 9, 13), result.get(1));
        }
    }

    @Test
    public void graphPreservesTheOriginalMultiTokenPathLength() throws Exception {
        SynonymMap rules = map("nyc, new york");
        try (Analyzer analyzer = analyzer(graph, () -> rules)) {
            List<Token> result = tokens(analyzer, "nyc");
            assertEquals(java.util.Set.of("nyc", "new", "york"),
                new java.util.HashSet<>(result.stream().map(Token::term).toList()));
            Token original = result.stream().filter(token -> token.term().equals("nyc")).findFirst().orElseThrow();
            assertEquals(graph ? 2 : 1, original.length());
            assertEquals(0, original.start());
            assertEquals(3, original.end());
            assertEquals(result, tokens(analyzer, "nyc"));
        }
    }

    @Test
    public void parallelReadersNeverMixPublishedVersions() throws Exception {
        SynonymMap old = map("a => old");
        SynonymMap next = map("a => new longer");
        AtomicReference<SynonymMap> maps = new AtomicReference<>(old);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        try (Analyzer analyzer = analyzer(graph, maps::get)) {
            List<Callable<Void>> work = new java.util.ArrayList<>();
            work.add(() -> { start.await(); for (int i=0;i<5000;i++) maps.set(i%2==0 ? old : next); return null; });
            for (int worker=0;worker<4;worker++) {
                work.add(() -> {
                    start.await();
                    for (int i=0;i<300;i++) {
                        List<String> actual = terms(analyzer, "a a a");
                        assertTrue(actual.toString(), actual.equals(List.of("old", "old", "old"))
                            || actual.equals(List.of("new", "longer", "new", "longer", "new", "longer")));
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = work.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Void> future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
