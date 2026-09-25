package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.text.ParseException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;

final class SynonymRuleParser {
    private SynonymRuleParser() { }

    static SynonymMap parse(Reader reader, String format, boolean expand,
                            boolean lenient, Analyzer analyzer) throws IOException {
        boolean wordnet = "wordnet".equalsIgnoreCase(format);
        SynonymMap.Parser parser = wordnet
            ? new WordnetSynonymParser(true, expand, analyzer)
            : new SolrSynonymParser(true, expand, analyzer);
        try {
            if (lenient) {
                parseLenient(reader, parser, wordnet);
            } else {
                parser.parse(reader);
            }
            return parser.build();
        } catch (ParseException | IllegalArgumentException e) {
            throw new IOException("Invalid synonym rules", e);
        }
    }

    private static void parseLenient(Reader reader, SynonymMap.Parser parser, boolean wordnet) throws IOException {
        BufferedReader lines = new BufferedReader(reader);
        if (!wordnet) {
            String line;
            while ((line = lines.readLine()) != null) {
                parseRule(parser, line);
            }
            return;
        }
        StringBuilder group = new StringBuilder();
        String groupId = null;
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.length() < 11 || !line.startsWith("s(")) {
                continue;
            }
            String id = line.substring(2, 11);
            if (groupId != null && !groupId.equals(id)) {
                parseRule(parser, group.toString());
                group.setLength(0);
            }
            group.append(line).append('\n');
            groupId = id;
        }
        if (!group.isEmpty()) {
            parseRule(parser, group.toString());
        }
    }

    private static void parseRule(SynonymMap.Parser parser, String rule) throws IOException {
        try {
            parser.parse(new StringReader(rule));
        } catch (ParseException | IllegalArgumentException | IOException ignored) {
            // lenient=true skips rules that the chain cannot parse or analyze.
        }
    }
}
