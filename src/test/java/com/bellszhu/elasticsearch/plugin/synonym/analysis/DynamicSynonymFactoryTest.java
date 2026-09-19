package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import static com.bellszhu.elasticsearch.plugin.synonym.analysis.SynonymTestSupport.*;
import static org.junit.Assert.*;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.*;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.analysis.AnalysisMode;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DynamicSynonymFactoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final TokenizerFactory TOKENIZER = TokenizerFactory.newFactory("whitespace", WhitespaceTokenizer::new);

    private DynamicSynonymTokenFilterFactory factory(boolean graph) throws Exception {
        Settings settings = Settings.builder().put("synonyms_path", "rules.txt").put("interval", 1).build();
        return graph ? new DynamicSynonymGraphTokenFilterFactory(environment(temp.getRoot().toPath()), "rules", settings)
            : new DynamicSynonymTokenFilterFactory(environment(temp.getRoot().toPath()), "rules", settings);
    }

    private TokenFilterFactory specialize(DynamicSynonymTokenFilterFactory factory, List<TokenFilterFactory> filters) {
        return factory.getChainAwareTokenFilterFactory(TOKENIZER, List.of(), filters, name -> null);
    }

    @Test
    public void pollingUpdatesInitiallyEmptyReusedAnalyzers() throws Exception {
        Path rules = temp.getRoot().toPath().resolve("rules.txt");
        Files.writeString(rules, "");
        for (boolean graph : new boolean[] {false, true}) {
            Files.writeString(rules, "");
            try (DynamicSynonymTokenFilterFactory factory = factory(graph);
                 Analyzer analyzer = analyzer(specialize(factory, List.of()))) {
                assertEquals(List.of("a"), terms(analyzer, "a"));
                Files.writeString(rules, "a => scheduled");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!terms(analyzer, "a").equals(List.of("scheduled")) && System.nanoTime() < deadline) Thread.sleep(25);
                assertEquals(List.of("scheduled"), terms(analyzer, "a"));
                Files.writeString(rules, "");
                factory.reloadSynonyms();
                assertEquals(List.of("a"), terms(analyzer, "a"));
            }
        }
    }

    @Test
    public void failuresKeepOldRulesAndRetrySameVersion() throws Exception {
        Path rules = temp.getRoot().toPath().resolve("rules.txt");
        for (boolean graph : new boolean[] {false, true}) {
            Files.writeString(rules, "a => old");
            try (DynamicSynonymTokenFilterFactory factory = factory(graph);
                 Analyzer analyzer = analyzer(specialize(factory, List.of()))) {
                Files.writeString(rules, "a => b => c");
                FileTime failedStamp = Files.getLastModifiedTime(rules);
                factory.reloadSynonyms();
                assertEquals(List.of("old"), terms(analyzer, "a"));
                Files.writeString(rules, "a => recovered");
                Files.setLastModifiedTime(rules, failedStamp);
                factory.reloadSynonyms();
                assertEquals(List.of("recovered"), terms(analyzer, "a"));
                Files.delete(rules);
                factory.reloadSynonyms();
                assertEquals(List.of("recovered"), terms(analyzer, "a"));
            }
        }
    }

    @Test
    public void eachAnalysisChainHasItsOwnParserAndBothVariantsAllowChaining() throws Exception {
        Path rules = temp.getRoot().toPath().resolve("rules.txt");
        TokenFilterFactory lower = new TokenFilterFactory() {
            public String name() { return "lowercase"; }
            public TokenStream create(TokenStream input) { return new LowerCaseFilter(input); }
        };
        for (boolean graph : new boolean[] {false, true}) {
            Files.writeString(rules, "ABC => VALUE");
            try (DynamicSynonymTokenFilterFactory factory = factory(graph)) {
                TokenFilterFactory plain = specialize(factory, List.of());
                TokenFilterFactory lowercase = specialize(factory, List.of(lower));
                assertSame(TokenFilterFactory.IDENTITY_FILTER, plain.getSynonymFilter());
                try (Analyzer first = analyzer(plain); Analyzer second = analyzer(lowercase)) {
                    assertEquals(List.of("VALUE"), terms(first, "ABC"));
                    assertEquals(List.of("value"), terms(second, "abc"));
                    Files.writeString(rules, "ABC => NEXT");
                    factory.reloadSynonyms();
                    assertEquals(List.of("NEXT"), terms(first, "ABC"));
                    assertEquals(List.of("next"), terms(second, "abc"));
                }
            }
        }
    }

    @Test
    public void ignoreCaseMatchesInputForBothVariants() throws Exception {
        Files.writeString(temp.getRoot().toPath().resolve("rules.txt"), "a => matched");
        Settings settings = Settings.builder().put("synonyms_path", "rules.txt").put("ignore_case", true).build();
        for (boolean graph : new boolean[] {false, true}) {
            var env = environment(temp.getRoot().toPath());
            try (DynamicSynonymTokenFilterFactory factory = graph
                    ? new DynamicSynonymGraphTokenFilterFactory(env, "rules", settings)
                    : new DynamicSynonymTokenFilterFactory(env, "rules", settings);
                 Analyzer analyzer = analyzer(specialize(factory, List.of()))) {
                assertEquals(List.of("matched"), terms(analyzer, "A"));
            }
        }
    }

    @Test
    public void validatesSettingsAndAnalysisMode() throws Exception {
        var env = environment(temp.getRoot().toPath());
        for (int interval : new int[] {0, -1}) {
            Settings settings = Settings.builder().put("synonyms_path", "rules.txt").put("interval", interval).build();
            assertThrows(IllegalArgumentException.class, () -> new DynamicSynonymTokenFilterFactory(env, "bad", settings));
        }
        assertThrows(IllegalArgumentException.class, () -> new DynamicSynonymTokenFilterFactory(env, "bad", Settings.EMPTY));
        Settings settings = Settings.builder().put("synonyms_path", "rules.txt").put("updateable", true).build();
        try (DynamicSynonymTokenFilterFactory factory = new DynamicSynonymTokenFilterFactory(env, "search", settings)) {
            assertEquals(AnalysisMode.SEARCH_TIME, factory.getAnalysisMode());
            assertThrows(IllegalArgumentException.class, () -> specialize(factory, List.of()));
            assertThrows(IllegalStateException.class, () -> specialize(factory, List.of()));
        }
    }

    @Test
    public void failedInitializationAndRepeatedCloseReleaseOwnershipOnce() throws Exception {
        DynamicSynonymTokenFilterFactory factory = factory(false);
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        factory.setCloseListener(closes::incrementAndGet);
        assertThrows(IllegalArgumentException.class, () -> specialize(factory, List.of()));
        factory.close();
        assertEquals(1, closes.get());
    }

    @Test
    public void concurrentSpecializationAndCloseDoNotScheduleAfterClose() throws Exception {
        Files.writeString(temp.getRoot().toPath().resolve("rules.txt"), "a => b");
        DynamicSynonymTokenFilterFactory factory = factory(false);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<TokenFilterFactory>> pending = new java.util.ArrayList<>();
            for (int i=0;i<8;i++) pending.add(executor.submit(() -> specialize(factory, List.of())));
            for (Future<TokenFilterFactory> future : pending) {
                try (Analyzer analyzer = analyzer(future.get(5, TimeUnit.SECONDS))) { assertEquals(List.of("b"), terms(analyzer, "a")); }
            }
            factory.close();
            factory.close();
            assertThrows(IllegalStateException.class, () -> specialize(factory, List.of()));
            factory.reloadSynonyms();
        } finally {
            factory.close();
            executor.shutdownNow();
        }
    }
}
