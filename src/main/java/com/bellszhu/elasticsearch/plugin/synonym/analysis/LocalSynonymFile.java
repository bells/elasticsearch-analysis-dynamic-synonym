package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.env.Environment;

/** @author bellszhu */
public class LocalSynonymFile implements SynonymFile {
    private final Path path;
    private final Analyzer analyzer;
    private final String format;
    private final boolean expand;
    private final boolean lenient;
    private Stamp committed;
    private boolean retry = true;

    LocalSynonymFile(Environment env, Analyzer analyzer, boolean expand, boolean lenient,
                     String format, String location) {
        this.path = env.configFile().resolve(location);
        this.analyzer = analyzer;
        this.format = format;
        this.expand = expand;
        this.lenient = lenient;
    }

    private Stamp stamp() throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        return new Stamp(attrs.lastModifiedTime(), attrs.size(), attrs.fileKey());
    }

    @Override
    public synchronized boolean isNeedReloadSynonymMap() throws IOException {
        try {
            return retry || !stamp().equals(committed);
        } catch (IOException e) {
            retry = true;
            throw e;
        }
    }

    @Override
    public synchronized SynonymMap reloadSynonymMap() throws IOException {
        retry = true;
        Stamp before = stamp();
        SynonymMap map;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            map = SynonymRuleParser.parse(reader, format, expand, lenient, analyzer);
        }
        if (!before.equals(stamp())) {
            throw new IOException("Synonym file changed while being read; retry on next poll");
        }
        committed = before;
        retry = false;
        return map;
    }

    private record Stamp(FileTime modified, long size, Object key) {
    }
}
