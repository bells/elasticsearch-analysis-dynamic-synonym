package com.bellszhu.elasticsearch.plugin;

import static org.elasticsearch.plugins.AnalysisPlugin.requiresAnalysisSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.indices.cluster.IndexRemovalReason;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;

import com.bellszhu.elasticsearch.plugin.synonym.analysis.DynamicSynonymGraphTokenFilterFactory;
import com.bellszhu.elasticsearch.plugin.synonym.analysis.DynamicSynonymTokenFilterFactory;

/** @author bellszhu */
public class DynamicSynonymPlugin extends Plugin implements AnalysisPlugin {
    private final Map<Index, List<DynamicSynonymTokenFilterFactory>> factories = new HashMap<>();
    private boolean closed;

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        return Map.of(
            "dynamic_synonym", requiresAnalysisSettings((indexSettings, env, name, settings) ->
                prepare(indexSettings.getIndex(), new DynamicSynonymTokenFilterFactory(env, name, settings))),
            "dynamic_synonym_graph", requiresAnalysisSettings((indexSettings, env, name, settings) ->
                prepare(indexSettings.getIndex(), new DynamicSynonymGraphTokenFilterFactory(env, name, settings)))
        );
    }

    private <T extends DynamicSynonymTokenFilterFactory> T prepare(Index index, T factory) {
        factory.setActivationListener(() -> register(index, factory));
        return factory;
    }

    private synchronized DynamicSynonymTokenFilterFactory register(Index index, DynamicSynonymTokenFilterFactory factory) {
        if (closed) {
            factory.close();
            throw new IllegalStateException("Dynamic synonym plugin is closed");
        }
        factory.setCloseListener(() -> unregister(index, factory));
        factories.computeIfAbsent(index, ignored -> new ArrayList<>()).add(factory);
        return factory;
    }

    private synchronized void unregister(Index index, DynamicSynonymTokenFilterFactory factory) {
        List<DynamicSynonymTokenFilterFactory> registered = factories.get(index);
        if (registered != null) {
            registered.remove(factory);
            if (registered.isEmpty()) {
                factories.remove(index);
            }
        }
    }

    @Override
    public void onIndexModule(IndexModule module) {
        module.addIndexEventListener(new IndexEventListener() {
            @Override
            public void afterIndexRemoved(Index index, IndexSettings settings, IndexRemovalReason reason) {
                closeIndex(index);
            }
        });
    }

    void closeIndex(Index index) {
        List<DynamicSynonymTokenFilterFactory> removed;
        synchronized (this) {
            removed = factories.remove(index);
        }
        if (removed != null) {
            removed.forEach(DynamicSynonymTokenFilterFactory::close);
        }
    }

    @Override
    public void close() throws IOException {
        List<DynamicSynonymTokenFilterFactory> remaining;
        synchronized (this) {
            closed = true;
            remaining = factories.values().stream().flatMap(List::stream).toList();
            factories.clear();
        }
        remaining.forEach(DynamicSynonymTokenFilterFactory::close);
        super.close();
    }
}
