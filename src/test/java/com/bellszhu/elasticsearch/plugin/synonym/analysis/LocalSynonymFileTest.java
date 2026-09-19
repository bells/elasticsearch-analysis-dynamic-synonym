package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import static com.bellszhu.elasticsearch.plugin.synonym.analysis.SynonymTestSupport.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.core.StopAnalyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalSynonymFileTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void relativePathBackwardTimestampDeletionAndRetry() throws Exception {
        Path root = temp.getRoot().toPath();
        Path path = root.resolve("rules.txt");
        try (Analyzer parser = new WhitespaceAnalyzer()) {
            LocalSynonymFile file = new LocalSynonymFile(environment(root), parser, true, false, "", "rules.txt");
            assertThrows(IOException.class, file::reloadSynonymMap);
            Files.writeString(path, "a => b");
            FileTime stamp = Files.getLastModifiedTime(path);
            file.reloadSynonymMap();
            assertFalse(file.isNeedReloadSynonymMap());
            Files.writeString(path, "a => c");
            Files.setLastModifiedTime(path, FileTime.fromMillis(stamp.toMillis()-5000));
            assertTrue(file.isNeedReloadSynonymMap());
            SynonymMap map = file.reloadSynonymMap();
            try (Analyzer analyzer = analyzer(false, () -> map)) { assertEquals(List.of("c"), terms(analyzer, "a")); }
            FileTime restored = Files.getLastModifiedTime(path);
            Files.delete(path);
            assertThrows(IOException.class, file::isNeedReloadSynonymMap);
            Files.writeString(path, "a => d");
            Files.setLastModifiedTime(path, restored);
            assertTrue(file.isNeedReloadSynonymMap());
            file.reloadSynonymMap();
            assertFalse(file.isNeedReloadSynonymMap());
        }
    }

    @Test
    public void invalidRulesDoNotCommitTimestampAndEmptyFileIsValid() throws Exception {
        Path root = temp.getRoot().toPath();
        Path path = root.resolve("rules.txt");
        try (Analyzer parser = new WhitespaceAnalyzer()) {
            LocalSynonymFile file = new LocalSynonymFile(environment(root), parser, true, false, "", path.toString());
            Files.writeString(path, "a => b => c");
            FileTime stamp = Files.getLastModifiedTime(path);
            assertThrows(IOException.class, file::reloadSynonymMap);
            Files.writeString(path, "a => fixed");
            Files.setLastModifiedTime(path, stamp);
            assertTrue(file.isNeedReloadSynonymMap());
            file.reloadSynonymMap();
            Files.writeString(path, "");
            assertTrue(file.isNeedReloadSynonymMap());
            assertNull(file.reloadSynonymMap().fst);
        }
    }

    @Test
    public void wordnetExpandAndLenientUseElasticsearchParsers() throws Exception {
        try (Analyzer parser = new WhitespaceAnalyzer()) {
            SynonymMap wordnet = SynonymRuleParser.parse(new StringReader(
                "s(100000001,1,'alpha',n,1,0).\ns(100000001,2,'beta',n,1,0).\n"), "wordnet", true, false, parser);
            try (Analyzer analyzer = analyzer(true, () -> wordnet)) {
                assertTrue(terms(analyzer, "alpha").contains("beta"));
            }
            SynonymMap collapsed = SynonymRuleParser.parse(new StringReader("a, b"), "", false, false, parser);
            try (Analyzer analyzer = analyzer(false, () -> collapsed)) { assertEquals(List.of("a"), terms(analyzer, "b")); }
        }
        try (Analyzer stop = new StopAnalyzer(new CharArraySet(List.of("the"), false))) {
            assertThrows(IOException.class, () -> SynonymRuleParser.parse(
                new StringReader("the => b"), "", true, false, stop));
            SynonymMap lenient = SynonymRuleParser.parse(new StringReader("the => b"), "", true, true, stop);
            assertNull(lenient.fst);
        }
    }
}
