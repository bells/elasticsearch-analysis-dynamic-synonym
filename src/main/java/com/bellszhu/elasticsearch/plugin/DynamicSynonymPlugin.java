package com.bellszhu.elasticsearch.plugin;

import static org.elasticsearch.plugins.AnalysisPlugin.requiresAnalysisSettings;

import java.util.HashMap;
import java.util.Map;

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

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        Map<String, AnalysisProvider<TokenFilterFactory>> extra = new HashMap<>();
        extra.put("dynamic_synonym", requiresAnalysisSettings((indexSettings, env, name, settings) -> new DynamicSynonymTokenFilterFactory(env, name, settings)));
        extra.put("dynamic_synonym_graph", requiresAnalysisSettings((indexSettings, env, name, settings) -> new DynamicSynonymGraphTokenFilterFactory(env, name, settings)));
        return extra;
    }
}