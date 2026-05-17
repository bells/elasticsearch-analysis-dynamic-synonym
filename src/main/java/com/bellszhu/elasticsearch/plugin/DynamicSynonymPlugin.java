package com.bellszhu.elasticsearch.plugin;

import static org.elasticsearch.plugins.AnalysisPlugin.requiresAnalysisSettings;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;

import com.bellszhu.elasticsearch.plugin.synonym.analysis.DynamicSynonymGraphTokenFilterFactory;
import com.bellszhu.elasticsearch.plugin.synonym.analysis.DynamicSynonymTokenFilterFactory;


/**
 * @author bellszhu
 */
public class DynamicSynonymPlugin extends Plugin implements AnalysisPlugin {

    private final List<DynamicSynonymTokenFilterFactory> tokenFilterFactories = Collections.synchronizedList(new ArrayList<>());

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        Map<String, AnalysisProvider<TokenFilterFactory>> extra = new HashMap<>();
        extra.put("dynamic_synonym", requiresAnalysisSettings((indexSettings, env, name, settings) -> {
            DynamicSynonymTokenFilterFactory factory = new DynamicSynonymTokenFilterFactory(env, name, settings);
            tokenFilterFactories.add(factory);
            return factory;
        }));
        extra.put("dynamic_synonym_graph", requiresAnalysisSettings((indexSettings, env, name, settings) -> {
            DynamicSynonymGraphTokenFilterFactory factory = new DynamicSynonymGraphTokenFilterFactory(env, name, settings);
            tokenFilterFactories.add(factory);
            return factory;
        }));
        return extra;
    }

    @Override
    public void close() throws IOException {
        synchronized (tokenFilterFactories) {
            for (DynamicSynonymTokenFilterFactory factory : tokenFilterFactories) {
                factory.close();
            }
            tokenFilterFactories.clear();
        }
        super.close();
    }
}
