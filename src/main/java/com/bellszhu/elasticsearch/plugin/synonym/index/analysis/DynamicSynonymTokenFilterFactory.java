package com.bellszhu.elasticsearch.plugin.synonym.index.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisSettingsRequired;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.index.analysis.TokenizerFactoryFactory;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.indices.IndicesLifecycle;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;

@AnalysisSettingsRequired
public class DynamicSynonymTokenFilterFactory extends
		AbstractTokenFilterFactory {
	private final URL synonymFileURL;

	private final String indexName;

	private final ThreadPool threadPool;
	private volatile ScheduledFuture scheduledFuture;
	private final TimeValue interval;

	private SynonymMap synonymMap;
	private final boolean ignoreCase;
	private final boolean expand;
	private final String format;
	private final Analyzer analyzer;

	private long lastModified;

	@Inject
	public DynamicSynonymTokenFilterFactory(Index index,
			@IndexSettings Settings indexSettings, Environment env,
			IndicesAnalysisService indicesAnalysisService,
			Map<String, TokenizerFactoryFactory> tokenizerFactories,
			@Assisted String name, @Assisted Settings settings,
			ThreadPool threadPool, IndicesService indicesService) {
		super(index, indexSettings, name, settings);

		Reader rulesReader = null;
		if (settings.get("synonyms_path") != null) {
			String filePath = settings.get("synonyms_path", null);
			synonymFileURL = env.resolveConfig(filePath);

			try {
				rulesReader = new InputStreamReader(
						synonymFileURL.openStream(), Charsets.UTF_8);
				lastModified = (new File(synonymFileURL.toURI()))
						.lastModified();
			} catch (Exception e) {
				String message = String.format(Locale.ROOT,
						"IOException while reading synonyms_path: %s",
						e.getMessage());
				throw new ElasticsearchIllegalArgumentException(message);
			}
		} else {
			throw new ElasticsearchIllegalArgumentException(
					"file watcher synonym requires `synonyms_path` to be configured");
		}

		this.indexName = index.getName();
		this.ignoreCase = settings.getAsBoolean("ignore_case", false);
		this.expand = settings.getAsBoolean("expand", true);
		this.format = settings.get("format");
		this.threadPool = threadPool;
		this.interval = settings.getAsTime("interval", timeValueSeconds(60));

		String tokenizerName = settings.get("tokenizer", "whitespace");

		TokenizerFactoryFactory tokenizerFactoryFactory = tokenizerFactories
				.get(tokenizerName);
		if (tokenizerFactoryFactory == null) {
			tokenizerFactoryFactory = indicesAnalysisService
					.tokenizerFactoryFactory(tokenizerName);
		}
		if (tokenizerFactoryFactory == null) {
			throw new ElasticsearchIllegalArgumentException(
					"failed to find tokenizer [" + tokenizerName
							+ "] for synonym token filter");
		}
		final TokenizerFactory tokenizerFactory = tokenizerFactoryFactory
				.create(tokenizerName, settings);

		this.analyzer = new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName,
					Reader reader) {
				Tokenizer tokenizer = tokenizerFactory == null ? new WhitespaceTokenizer(
						Lucene.ANALYZER_VERSION, reader) : tokenizerFactory
						.create(reader);
				TokenStream stream = ignoreCase ? new LowerCaseFilter(
						Lucene.ANALYZER_VERSION, tokenizer) : tokenizer;
				return new TokenStreamComponents(tokenizer, stream);
			}
		};

		try {
			SynonymMap.Builder parser = null;

			if ("wordnet".equalsIgnoreCase(settings.get("format"))) {
				parser = new WordnetSynonymParser(true, expand, analyzer);
				((WordnetSynonymParser) parser).parse(rulesReader);
			} else {
				parser = new SolrSynonymParser(true, expand, analyzer);
				((SolrSynonymParser) parser).parse(rulesReader);
			}

			synonymMap = parser.build();
		} catch (Exception e) {
			throw new ElasticsearchIllegalArgumentException(
					"failed to build synonyms", e);
		}

		scheduledFuture = threadPool.scheduleWithFixedDelay(new FileMonitor(),
				interval);
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
		// fst is null means no synonyms
		return synonymMap.fst == null ? tokenStream : new SynonymFilter(
				tokenStream, synonymMap, ignoreCase);
	}

	public class FileMonitor implements Runnable {
		@Override
		public void run() {
			try {
				File synonymFile = new File(synonymFileURL.toURI());
				if (synonymFile.exists()
						&& lastModified < synonymFile.lastModified()) {
					Reader rulesReader = new InputStreamReader(
							synonymFileURL.openStream(), Charsets.UTF_8);

					SynonymMap.Builder parser = null;

					if ("wordnet".equalsIgnoreCase(format)) {
						parser = new WordnetSynonymParser(true, expand,
								analyzer);
						((WordnetSynonymParser) parser).parse(rulesReader);
					} else {
						parser = new SolrSynonymParser(true, expand, analyzer);
						((SolrSynonymParser) parser).parse(rulesReader);
					}

					synonymMap = parser.build();
					lastModified = synonymFile.lastModified();
				}
			} catch (Exception e) {
				throw new RuntimeException("could not reload synonyms file: "
						+ e.getMessage());
			}
		}
	}
}