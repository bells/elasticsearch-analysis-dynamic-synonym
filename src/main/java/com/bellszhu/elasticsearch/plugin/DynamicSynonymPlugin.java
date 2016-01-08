/**
 * 
 */
package com.bellszhu.elasticsearch.plugin;

import org.elasticsearch.common.inject.Module;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.plugins.AbstractPlugin;

import com.bellszhu.elasticsearch.plugin.synonym.analysis.DynamicSynonymTokenFilterFactory;

/**
 * @author bellszhu
 *
 */
public class DynamicSynonymPlugin extends AbstractPlugin {

	@Override
	public String description() {
		return "Analysis-plugin for synonym";
	}

	@Override
	public String name() {
		return "analysis-dynamic-synonym";
	}

	@Override
	public void processModule(Module module) {
		if (module instanceof AnalysisModule) {
			AnalysisModule analysisModule = (AnalysisModule) module;
			analysisModule.addTokenFilter("dynamic_synonym",
					DynamicSynonymTokenFilterFactory.class);
		}
	}

}
