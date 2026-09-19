package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import java.io.Reader;
import java.text.ParseException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.analysis.common.ESSolrSynonymParser;
import org.elasticsearch.analysis.common.ESWordnetSynonymParser;

final class SynonymRuleParser {
    private SynonymRuleParser() {
    }

    static SynonymMap parse(Reader reader, String format, boolean expand,
                            boolean lenient, Analyzer analyzer) throws IOException {
        SynonymMap.Parser parser = "wordnet".equalsIgnoreCase(format)
            ? new ESWordnetSynonymParser(true, expand, lenient, analyzer)
            : new ESSolrSynonymParser(true, expand, lenient, analyzer);
        try {
            parser.parse(reader);
            return parser.build();
        } catch (ParseException | IllegalArgumentException e) {
            throw new IOException("Invalid synonym rules", e);
        }
    }
}
