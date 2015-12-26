package com.bellszhu.elasticsearch.plugin.synonym.index.analysis;

import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
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
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisSettingsRequired;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.index.analysis.TokenizerFactoryFactory;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.indices.IndicesLifecycle;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;
import org.elasticsearch.threadpool.ThreadPool;

import com.bellszhu.elasticsearch.cfg.Configuration;

@AnalysisSettingsRequired
public class DynamicSynonymTokenFilterFactory extends
		AbstractTokenFilterFactory {

	public static ESLogger logger = Loggers.getLogger("dynamic-synonym");

	private final URL synonymFileURL;

	private String location;

	private final String indexName;
	private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);
	private final ThreadPool threadPool;
	private volatile ScheduledFuture scheduledFuture;
	private final TimeValue interval;

	private SynonymMap synonymMap;
	private final boolean ignoreCase;
	private final boolean expand;
	private final String format;
	private final Analyzer analyzer;

	private Configuration configuration;

	private long lastModified;

	@Inject
	public DynamicSynonymTokenFilterFactory(Index index,
			@IndexSettings Settings indexSettings, Environment env,
			IndicesAnalysisService indicesAnalysisService,
			Map<String, TokenizerFactoryFactory> tokenizerFactories,
			@Assisted String name, @Assisted Settings settings,
			ThreadPool threadPool, IndicesService indicesService) {
		super(index, indexSettings, name, settings);
		
		configuration = new Configuration(env);

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

		scheduledFuture = pool.scheduleAtFixedRate(new RemoteFileMonitor(location), 10, 60, TimeUnit.SECONDS);
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

	public class LocalFileMonitor implements Runnable {
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

	public class RemoteFileMonitor implements Runnable {

		private CloseableHttpClient httpclient = HttpClients.createDefault();

		/** 上次更改时间 */
		private String last_modified;

		/** 资源属性 */
		private String eTags;

		/** 请求地址 */
		private String location;

		public RemoteFileMonitor(String location) {
			this.location = location;
			this.last_modified = null;
			this.eTags = null;
		}

		/**
		 * 监控流程： ①向词库服务器发送Head请求 ②从响应中获取Last-Modify、ETags字段值，判断是否变化
		 * ③如果未变化，休眠1min，返回第①步 ④如果有变化，重新加载词典 ⑤休眠1min，返回第①步
		 */

		public void run() {

			// 超时设置
			RequestConfig rc = RequestConfig.custom()
					.setConnectionRequestTimeout(10 * 1000)
					.setConnectTimeout(10 * 1000).setSocketTimeout(15 * 1000)
					.build();

			HttpHead head = new HttpHead(location);
			head.setConfig(rc);

			// 设置请求头
			if (last_modified != null) {
				head.setHeader("If-Modified-Since", last_modified);
			}
			if (eTags != null) {
				head.setHeader("If-None-Match", eTags);
			}

			CloseableHttpResponse response = null;
			try {

				response = httpclient.execute(head);

				// 返回200 才做操作
				if (response.getStatusLine().getStatusCode() == 200) {

					if (!response.getLastHeader("Last-Modified").getValue()
							.equalsIgnoreCase(last_modified)
							|| !response.getLastHeader("ETag").getValue()
									.equalsIgnoreCase(eTags)) {

						// 远程词库有更新,需要重新加载词典，并修改last_modified,eTags
						// Dictionary.getSingleton().reLoadMainDict();
						Reader rulesReader = loadRemoteExtDict();

						SynonymMap.Builder parser = null;

						if ("wordnet".equalsIgnoreCase(format)) {
							parser = new WordnetSynonymParser(true, expand,
									analyzer);
							((WordnetSynonymParser) parser).parse(rulesReader);
						} else {
							parser = new SolrSynonymParser(true, expand,
									analyzer);
							((SolrSynonymParser) parser).parse(rulesReader);
						}

						synonymMap = parser.build();

						last_modified = response.getLastHeader("Last-Modified") == null ? null
								: response.getLastHeader("Last-Modified")
										.getValue();
						eTags = response.getLastHeader("ETag") == null ? null
								: response.getLastHeader("ETag").getValue();
					}
				} else if (response.getStatusLine().getStatusCode() == 304) {
					// 没有修改，不做操作
					// noop
				} else {
					// Dictionary.logger.info("remote_ext_dict {} return bad code {}"
					// , location , response.getStatusLine().getStatusCode() );
					logger.info("remote_ext_dict {} return bad code {}", location , response.getStatusLine().getStatusCode());
				}

			} catch (Exception e) {
				logger.error("remote_ext_dict {} error!",e , location);
				// Dictionary.logger.error("remote_ext_dict {} error!",e ,
				// location);
			} finally {
				try {
					if (response != null) {
						response.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		/**
		 * 加载远程扩展词典到主词库表
		 */
		private Reader loadRemoteExtDict() {
			List<String> remoteExtDictFiles = configuration
					.getRemoteExtDictionarys();
			Reader in = getRemoteWords(remoteExtDictFiles.get(0));
			/*
			for (String location : remoteExtDictFiles) {
				logger.info("[Dict Loading]" + location);
				Reader in = getRemoteWords(location);
				return in;
				// 如果找不到扩展的字典，则忽略
				if (lists == null) {
					logger.error("[Dict Loading]" + location + "加载失败");
					continue;
				}
				for (String theWord : lists) {
					if (theWord != null && !"".equals(theWord.trim())) {
						// 加载扩展词典数据到主内存词典中
						logger.info(theWord);
						_MainDict.fillSegment(theWord.trim().toLowerCase()
								.toCharArray());
					}
				}
			}*/
			return in;

		}

		/**
		 * 从远程服务器上下载自定义词条
		 */
		private Reader getRemoteWords(String location) {

			List<String> buffer = new ArrayList<String>();
			RequestConfig rc = RequestConfig.custom()
					.setConnectionRequestTimeout(10 * 1000)
					.setConnectTimeout(10 * 1000).setSocketTimeout(60 * 1000)
					.build();
			CloseableHttpClient httpclient = HttpClients.createDefault();
			CloseableHttpResponse response;
			BufferedReader in = null;
			HttpGet get = new HttpGet(location);
			get.setConfig(rc);
			try {
				response = httpclient.execute(get);
				if (response.getStatusLine().getStatusCode() == 200) {

					String charset = "UTF-8";
					// 获取编码，默认为utf-8
					if (response.getEntity().getContentType().getValue()
							.contains("charset=")) {
						String contentType = response.getEntity()
								.getContentType().getValue();
						charset = contentType.substring(contentType
								.lastIndexOf("=") + 1);
					}
					in = new BufferedReader(new InputStreamReader(response
							.getEntity().getContent(), charset));
					/*String line;
					while ((line = in.readLine()) != null) {
						buffer.add(line);
					}
					in.close();*/
					response.close();
					return in;
				}
				response.close();
			} catch (ClientProtocolException e) {
				logger.error("getRemoteWords {} error", e, location);
			} catch (IllegalStateException e) {
				logger.error("getRemoteWords {} error", e, location);
			} catch (IOException e) {
				logger.error("getRemoteWords {} error", e, location);
			}
			return in;
		}
	}
}