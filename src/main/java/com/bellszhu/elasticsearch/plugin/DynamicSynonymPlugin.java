/**
 *
 */
package com.bellszhu.elasticsearch.plugin;

import com.bellszhu.elasticsearch.plugin.synonym.analysis.DynamicSynonymGraphTokenFilterFactory;
import com.bellszhu.elasticsearch.plugin.synonym.analysis.DynamicSynonymTokenFilterFactory;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.indices.analysis.AnalysisModule;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;

import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.plugins.AnalysisPlugin.requiresAnalysisSettings;


/**
 * @author bellszhu
 */
public class DynamicSynonymPlugin extends Plugin implements AnalysisPlugin {

    @Override
    public Map<String, AnalysisModule.AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        Map<String, AnalysisModule.AnalysisProvider<TokenFilterFactory>> extra = new HashMap<>();
        extra.put("dynamic_synonym", requiresAnalysisSettings(DynamicSynonymTokenFilterFactory::new));
        extra.put("dynamic_synonym_graph", requiresAnalysisSettings(DynamicSynonymGraphTokenFilterFactory::new));
        return extra;
    }
}