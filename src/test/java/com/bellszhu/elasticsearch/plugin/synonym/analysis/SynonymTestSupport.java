package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.*;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.analysis.TokenFilterFactory;

final class SynonymTestSupport {
    static Environment environment(Path root) {
        return new Environment(Settings.builder().put("path.home", root).build(), root);
    }

    static SynonymMap map(String rules) throws IOException {
        try (Analyzer analyzer = new WhitespaceAnalyzer()) {
            return SynonymRuleParser.parse(new StringReader(rules), "", true, false, analyzer);
        }
    }

    static Analyzer analyzer(boolean graph, Supplier<SynonymMap> maps) {
        return analyzer(new TokenFilterFactory() {
            public String name() { return "test"; }
            public TokenStream create(TokenStream input) {
                return graph ? new DynamicSynonymGraphFilter(input, maps, false)
                    : new DynamicSynonymFilter(input, maps, false);
            }
        });
    }

    static Analyzer analyzer(TokenFilterFactory factory) {
        return new Analyzer() {
            protected TokenStreamComponents createComponents(String field) {
                WhitespaceTokenizer tokenizer = new WhitespaceTokenizer();
                return new TokenStreamComponents(tokenizer, factory.create(tokenizer));
            }
        };
    }

    static List<String> terms(Analyzer analyzer, String text) throws IOException {
        return tokens(analyzer, text).stream().map(Token::term).toList();
    }

    static List<Token> tokens(Analyzer analyzer, String text) throws IOException {
        try (TokenStream stream = analyzer.tokenStream("field", text)) {
            stream.reset();
            List<Token> result = drain(stream);
            stream.end();
            return result;
        }
    }

    static List<Token> drain(TokenStream stream) throws IOException {
        List<Token> result = new ArrayList<>();
        CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
        TypeAttribute type = stream.addAttribute(TypeAttribute.class);
        PositionIncrementAttribute inc = stream.addAttribute(PositionIncrementAttribute.class);
        PositionLengthAttribute len = stream.addAttribute(PositionLengthAttribute.class);
        OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
        while (stream.incrementToken()) {
            result.add(new Token(term.toString(), type.type(), inc.getPositionIncrement(),
                len.getPositionLength(), offset.startOffset(), offset.endOffset()));
        }
        return result;
    }

    record Token(String term, String type, int increment, int length, int start, int end) { }
}
