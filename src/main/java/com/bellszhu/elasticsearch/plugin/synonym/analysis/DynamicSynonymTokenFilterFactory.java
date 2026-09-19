package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisMode;
import org.elasticsearch.index.analysis.CharFilterFactory;
import org.elasticsearch.index.analysis.CustomAnalyzer;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;

/** @author bellszhu */
public class DynamicSynonymTokenFilterFactory extends AbstractTokenFilterFactory implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger("dynamic-synonym");
    private static final AtomicInteger IDS = new AtomicInteger();
    private final ScheduledThreadPoolExecutor pool;
    private final List<ChainState> chains = new ArrayList<>();
    private final Environment environment;
    private final String location;
    private final String format;
    private final boolean expand;
    private final boolean lenient;
    private final boolean ignoreCase;
    private final int interval;
    private final AnalysisMode analysisMode;
    private boolean monitoring;
    private boolean closed;
    private Runnable closeListener = () -> { };

    public DynamicSynonymTokenFilterFactory(Environment env, String name, Settings settings) throws IOException {
        super(name, settings);
        location = settings.get("synonyms_path");
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("dynamic synonym requires a non-empty `synonyms_path`");
        }
        interval = settings.getAsInt("interval", 60);
        if (interval <= 0) {
            throw new IllegalArgumentException("dynamic synonym `interval` must be greater than zero");
        }
        ignoreCase = settings.getAsBoolean("ignore_case", false);
        expand = settings.getAsBoolean("expand", true);
        lenient = settings.getAsBoolean("lenient", false);
        format = settings.get("format", "");
        analysisMode = settings.getAsBoolean("updateable", false) ? AnalysisMode.SEARCH_TIME : AnalysisMode.ALL;
        environment = env;
        pool = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "monitor-synonym-Thread-" + IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
    }

    @Override
    public AnalysisMode getAnalysisMode() {
        return analysisMode;
    }

    @Override
    public TokenStream create(TokenStream input) {
        throw new IllegalStateException("Specialize this factory for an analysis chain first");
    }

    @Override
    public synchronized TokenFilterFactory getChainAwareTokenFilterFactory(
            TokenizerFactory tokenizer, List<CharFilterFactory> charFilters,
            List<TokenFilterFactory> previousTokenFilters, Function<String, TokenFilterFactory> allFilters) {
        if (closed) {
            throw new IllegalStateException("Dynamic synonym factory is closed");
        }
        Analyzer analyzer = new CustomAnalyzer(tokenizer, charFilters.toArray(new CharFilterFactory[0]),
            previousTokenFilters.stream().map(TokenFilterFactory::getSynonymFilter).toArray(TokenFilterFactory[]::new));
        SynonymFile source = null;
        try {
            source = location.startsWith("http://") || location.startsWith("https://")
                ? new RemoteSynonymFile(environment, analyzer, expand, lenient, format, location)
                : new LocalSynonymFile(environment, analyzer, expand, lenient, format, location);
            ChainState state = new ChainState(analyzer, source, source.reloadSynonymMap());
            chains.add(state);
            if (!monitoring) {
                pool.scheduleWithFixedDelay(this::reloadSynonyms, interval, interval, TimeUnit.SECONDS);
                monitoring = true;
            }
            return new TokenFilterFactory() {
                @Override
                public String name() {
                    return DynamicSynonymTokenFilterFactory.this.name();
                }

                @Override
                public TokenStream create(TokenStream input) {
                    return newFilter(input, state::snapshot, ignoreCase);
                }

                @Override
                public TokenFilterFactory getSynonymFilter() {
                    return IDENTITY_FILTER;
                }

                @Override
                public AnalysisMode getAnalysisMode() {
                    return analysisMode;
                }
            };
        } catch (Exception e) {
            if (source != null) {
                try {
                    source.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
            }
            analyzer.close();
            if (chains.isEmpty()) {
                close();
            }
            throw new IllegalArgumentException("Failed to load initial synonym rules for filter " + name(), e);
        }
    }

    protected TokenStream newFilter(TokenStream input, Supplier<SynonymMap> snapshots, boolean ignoreCase) {
        return new DynamicSynonymFilter(input, snapshots, ignoreCase);
    }

    public synchronized void setCloseListener(Runnable listener) {
        if (closed) {
            throw new IllegalStateException("Dynamic synonym factory is closed");
        }
        closeListener = listener;
    }

    void reloadSynonyms() {
        List<ChainState> current;
        synchronized (this) {
            if (closed) {
                return;
            }
            current = new ArrayList<>(chains);
        }
        for (ChainState state : current) {
            state.reload();
        }
    }

    @Override
    public void close() {
        List<ChainState> current;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pool.shutdownNow();
            current = new ArrayList<>(chains);
            chains.clear();
        }
        try {
            for (ChainState state : current) {
                state.close();
            }
        } finally {
            closeListener.run();
        }
    }

    private final class ChainState implements AutoCloseable {
        private final Analyzer analyzer;
        private final SynonymFile source;
        private volatile SynonymMap map;
        private volatile boolean stopped;

        private ChainState(Analyzer analyzer, SynonymFile source, SynonymMap map) {
            this.analyzer = analyzer;
            this.source = source;
            this.map = map;
        }

        private SynonymMap snapshot() {
            return map;
        }

        private synchronized void reload() {
            if (stopped) {
                return;
            }
            try {
                if (source.isNeedReloadSynonymMap()) {
                    SynonymMap next = source.reloadSynonymMap();
                    if (!stopped) {
                        map = next;
                        logger.debug("Reloaded synonym filter {}", name());
                    }
                }
            } catch (Exception e) {
                if (!stopped) {
                    // Parser errors and HTTP exceptions can include rule contents or credential-bearing URLs.
                    logger.warn("Keeping previous rules for synonym filter {} after {}", name(), e.getClass().getSimpleName());
                }
            }
        }

        @Override
        public void close() {
            stopped = true;
            try {
                source.close();
            } catch (IOException e) {
                logger.warn("Failed to close synonym source for filter {}", name());
            }
            synchronized (this) {
                analyzer.close();
            }
        }
    }
}
