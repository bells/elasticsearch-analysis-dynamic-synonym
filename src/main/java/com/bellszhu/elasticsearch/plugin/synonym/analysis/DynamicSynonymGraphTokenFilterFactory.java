package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import java.util.function.Supplier;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

public class DynamicSynonymGraphTokenFilterFactory extends DynamicSynonymTokenFilterFactory {
    public DynamicSynonymGraphTokenFilterFactory(Environment env, String name, Settings settings) throws IOException {
        super(env, name, settings);
    }

    @Override
    protected TokenStream newFilter(TokenStream input, Supplier<SynonymMap> snapshots, boolean ignoreCase) {
        return new DynamicSynonymGraphFilter(input, snapshots, ignoreCase);
    }
}
