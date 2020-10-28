package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalysisMode;
import org.elasticsearch.index.analysis.CharFilterFactory;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

public class DynamicSynonymGraphTokenFilterFactory extends DynamicSynonymTokenFilterFactory {

    public DynamicSynonymGraphTokenFilterFactory(
            IndexSettings indexSettings, Environment env, String name, Settings settings
    ) throws IOException {
        super(indexSettings, env, name, settings);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        throw new IllegalStateException(
                "Call createPerAnalyzerSynonymGraphFactory to specialize this factory for an analysis chain first"
        );
    }

    @Override
    public TokenFilterFactory getChainAwareTokenFilterFactory(
            TokenizerFactory tokenizer, List<CharFilterFactory> charFilters,
            List<TokenFilterFactory> previousTokenFilters,
            Function<String, TokenFilterFactory> allFilters
    ) {
        final Analyzer analyzer = buildSynonymAnalyzer(tokenizer, charFilters, previousTokenFilters, allFilters);
        synonymMap = buildSynonyms(analyzer);
        final String name = name();
        return new TokenFilterFactory() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public TokenStream create(TokenStream tokenStream) {
                // fst is null means no synonyms
                if (synonymMap.fst == null) {
                    return tokenStream;
                }
                DynamicSynonymGraphFilter dynamicSynonymGraphFilter = new DynamicSynonymGraphFilter(
                        tokenStream, synonymMap, false);
                dynamicSynonymFilters.put(dynamicSynonymGraphFilter, 1);

                return dynamicSynonymGraphFilter;
            }

            @Override
            public AnalysisMode getAnalysisMode() {
                return analysisMode;
            }
        };
    }
}
