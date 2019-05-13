package com.bellszhu.elasticsearch.plugin.synonym.service;

import com.bellszhu.elasticsearch.plugin.DynamicSynonymPlugin;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.index.analysis.AnalysisRegistry;

public class DynamicSynonymAnalysisService extends AbstractLifecycleComponent {

    @Inject
    public DynamicSynonymAnalysisService(final AnalysisRegistry analysisRegistry,
                                         final DynamicSynonymPlugin.PluginComponent pluginComponent) {
        super();
        pluginComponent.setAnalysisRegistry(analysisRegistry);
    }

    @Override
    protected void doStart() {
        // nothing
    }

    @Override
    protected void doStop() {
        // nothing
    }

    @Override
    protected void doClose() {
        // nothing
    }

}
