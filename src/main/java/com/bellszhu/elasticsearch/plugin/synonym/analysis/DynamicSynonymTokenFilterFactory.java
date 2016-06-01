package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisSettingsRequired;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.index.analysis.TokenizerFactoryFactory;
import org.elasticsearch.index.settings.IndexSettingsService;
import org.elasticsearch.indices.IndicesLifecycle;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;

/**
 * 
 * @author bellszhu
 *
 */
@AnalysisSettingsRequired
public class DynamicSynonymTokenFilterFactory extends
		AbstractTokenFilterFactory {

	public static ESLogger logger = Loggers.getLogger("dynamic-synonym");

	private ScheduledExecutorService pool;

	private volatile ScheduledFuture<?> scheduledFuture;

	private final String indexName;

	private final String location;
	private final boolean ignoreCase;
	private final boolean expand;
	private final String format;
	private final int interval;

	private SynonymMap synonymMap;
	private Map<DynamicSynonymFilter, Integer> dynamicSynonymFilters = new WeakHashMap<DynamicSynonymFilter, Integer>();

	@Inject
	public DynamicSynonymTokenFilterFactory(Index index,
			IndexSettingsService indexSettingsService, Environment env,
			IndicesAnalysisService indicesAnalysisService,
			Map<String, TokenizerFactoryFactory> tokenizerFactories,
			@Assisted String name, @Assisted Settings settings,
			IndicesService indicesService) {
		super(index, indexSettingsService.getSettings(), name, settings);

		this.indexName = index.getName();

		this.location = settings.get("synonyms_path");
		if (this.location == null) {
			throw new IllegalArgumentException(
					"dynamic synonym requires `synonyms_path` to be configured");
		}

		this.interval = settings.getAsInt("interval", 60);
		this.ignoreCase = settings.getAsBoolean("ignore_case", false);
		this.expand = settings.getAsBoolean("expand", true);
		this.format = settings.get("format", "");

		pool = Executors.newScheduledThreadPool(1);

		String tokenizerName = settings.get("tokenizer", "whitespace");
		TokenizerFactoryFactory tokenizerFactoryFactory = tokenizerFactories
				.get(tokenizerName);
		if (tokenizerFactoryFactory == null) {
			tokenizerFactoryFactory = indicesAnalysisService
					.tokenizerFactoryFactory(tokenizerName);
		}
		if (tokenizerFactoryFactory == null) {
			throw new IllegalArgumentException("failed to find tokenizer ["
					+ tokenizerName + "] for synonym token filter");
		}

		final TokenizerFactory tokenizerFactory = tokenizerFactoryFactory
				.create(
						tokenizerName,
						Settings.builder().put(indexSettingsService.getSettings()).put(settings).build()
				);

		Analyzer analyzer = new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				Tokenizer tokenizer = tokenizerFactory == null ? new WhitespaceTokenizer()
						: tokenizerFactory.create();
				TokenStream stream = ignoreCase ? new LowerCaseFilter(tokenizer)
						: tokenizer;
				return new TokenStreamComponents(tokenizer, stream);
			}
		};

		SynonymFile synonymFile;
		if (location.startsWith("http://")) {
			synonymFile = new RemoteSynonymFile(env, analyzer, expand, format,
					location);
		} else {
			synonymFile = new LocalSynonymFile(env, analyzer, expand, format,
					location);
		}
		synonymMap = synonymFile.reloadSynonymMap();

		scheduledFuture = pool.scheduleAtFixedRate(new Monitor(synonymFile),
				interval, interval, TimeUnit.SECONDS);
		indicesService.indicesLifecycle().addListener(
				new IndicesLifecycle.Listener() {
					@Override
					public void beforeIndexClosed(IndexService indexService) {
						if (indexService.index().getName().equals(indexName)) {
							scheduledFuture.cancel(false);
						}
					}
				});
	}

	@Override
	public TokenStream create(TokenStream tokenStream) {
		DynamicSynonymFilter dynamicSynonymFilter = new DynamicSynonymFilter(
				tokenStream, synonymMap, ignoreCase);
		dynamicSynonymFilters.put(dynamicSynonymFilter, 1);

		// fst is null means no synonyms
		return synonymMap.fst == null ? tokenStream : dynamicSynonymFilter;
	}

	public class Monitor implements Runnable {

		private SynonymFile synonymFile;

		public Monitor(SynonymFile synonymFile) {
			this.synonymFile = synonymFile;
		}

		@Override
		public void run() {
			if (synonymFile.isNeedReloadSynonymMap()) {
				synonymMap = synonymFile.reloadSynonymMap();
				for (DynamicSynonymFilter dynamicSynonymFilter : dynamicSynonymFilters
						.keySet()) {
					dynamicSynonymFilter.update(synonymMap);
					logger.info("{} success reload synonym", indexName);
				}
			}
		}
	}

}