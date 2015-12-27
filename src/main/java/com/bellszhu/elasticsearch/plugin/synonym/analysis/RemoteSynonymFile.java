/**
 * 
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Locale;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.env.Environment;

/**
 * @author bells
 *
 */
public class RemoteSynonymFile implements SynonymFile {
	
	public static ESLogger logger = Loggers.getLogger("dynamic-synonym");
	
	private CloseableHttpClient httpclient = HttpClients.createDefault();
	
	private String filePath;

	private String format;

	private boolean expand;

	private Analyzer analyzer;

	private Environment env;
	
	/** 上次更改时间 */
	private String lastModified;

	/** 资源属性 */
	private String eTags;

	/** 请求地址 */
	private String location;
	
	/*
	public void run() {

		logger.info("*****start location: " + location);

		// 超时设置
		RequestConfig rc = RequestConfig.custom()
				.setConnectionRequestTimeout(10 * 1000)
				.setConnectTimeout(10 * 1000).setSocketTimeout(15 * 1000)
				.build();

		HttpHead head = new HttpHead(location);
		head.setConfig(rc);

		// 设置请求头
		if (lastModified != null) {
			head.setHeader("If-Modified-Since", lastModified);
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
						.equalsIgnoreCase(lastModified)
						|| !response.getLastHeader("ETag").getValue()
								.equalsIgnoreCase(eTags)) {

					// 远程词库有更新,需要重新加载词典，并修改last_modified,eTags
					// Dictionary.getSingleton().reLoadMainDict();
					Reader rulesReader = getReader(false);

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

					lastModified = response.getLastHeader("Last-Modified") == null ? null
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
				logger.info("remote_ext_dict {} return bad code {}",
						location, response.getStatusLine().getStatusCode());
			}

		} catch (Exception e) {
			logger.error("remote_ext_dict {} error!", e, location);
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
	*/
	
	@Override
	public SynonymMap createSynonymMap() {
		Reader rulesReader = getReader();

		SynonymMap.Builder parser = null;

		try {
			if ("wordnet".equalsIgnoreCase(format)) {
				parser = new WordnetSynonymParser(true, expand, analyzer);
				((WordnetSynonymParser) parser).parse(rulesReader);
			} else {
				parser = new SolrSynonymParser(true, expand, analyzer);
				((SolrSynonymParser) parser).parse(rulesReader);
			}
			
			return parser.build();
		} catch (Exception e) {
			throw new ElasticsearchIllegalArgumentException(
					"failed to build synonyms", e);
		}
		
	}
	
	/**
	 * 从远程服务器上下载自定义词条
	 */
	public Reader getReader() {

		RequestConfig rc = RequestConfig.custom()
				.setConnectionRequestTimeout(10 * 1000)
				.setConnectTimeout(10 * 1000).setSocketTimeout(60 * 1000)
				.build();
		CloseableHttpClient httpclient = HttpClients.createDefault();
		CloseableHttpResponse response;
		BufferedReader in = null;
		HttpGet get = new HttpGet(filePath);
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
			}
			response.close();
		} catch (IOException e) {
				String message = String.format(Locale.ROOT,
						"IOException while reading %s_path: %s", filePath,
						e.getMessage());
				throw new ElasticsearchIllegalArgumentException(message);
		}
		return in;
	}

}
