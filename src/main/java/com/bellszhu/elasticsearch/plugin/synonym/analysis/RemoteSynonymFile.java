/**
 * 
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.env.Environment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * @author bellszhu
 *
 */
public class RemoteSynonymFile implements SynonymFile {

	public static Logger logger = ESLoggerFactory.getLogger("dynamic-synonym");

	private CloseableHttpClient httpclient = HttpClients.createDefault();

	private String format;

	private boolean expand;

	private Analyzer analyzer;

	private Environment env;

	/** 远程url地址 */
	private String location;

	/** 上次更改时间 */
	private String lastModified;

	/** 资源属性 */
	private String eTags;

	public RemoteSynonymFile(Environment env, Analyzer analyzer,
			boolean expand, String format, String location) {
		this.analyzer = analyzer;
		this.expand = expand;
		this.format = format;
		this.env = env;
		this.location = location;

		isNeedReloadSynonymMap();
	}

	@Override
	public SynonymMap reloadSynonymMap() {
		Reader rulesReader = null;
		try {
			logger.info("start reload remote synonym from {}.", location);
			rulesReader = getReader();
			SynonymMap.Builder parser = null;

			if ("wordnet".equalsIgnoreCase(format)) {
				parser = new WordnetSynonymParser(true, expand, analyzer);
				((WordnetSynonymParser) parser).parse(rulesReader);
			} else {
				parser = new SolrSynonymParser(true, expand, analyzer);
				((SolrSynonymParser) parser).parse(rulesReader);
			}
			return parser.build();
		} catch (Exception e) {
			logger.error("reload remote synonym {} error!", e, location);
			throw new IllegalArgumentException(
					"could not reload remote synonyms file to build synonyms",
					e);
		}
		finally {
			if(rulesReader != null ){
				try {
					rulesReader.close();
				} catch(Exception e) {
					logger.error("failed to close rulesReader", e);
				}
			}
		}
	}

	/**
	 * 从远程服务器上下载自定义词条
	 */
	public Reader getReader() {
		Reader reader = null;
		RequestConfig rc = RequestConfig.custom()
				.setConnectionRequestTimeout(10 * 1000)
				.setConnectTimeout(10 * 1000).setSocketTimeout(60 * 1000)
				.build();
		CloseableHttpResponse response = null;
		BufferedReader br = null;
		HttpGet get = new HttpGet(location);
		get.setConfig(rc);
		try {
			response = httpclient.execute(get);
			if (response.getStatusLine().getStatusCode() == 200) {
				String charset = "UTF-8"; // 获取编码，默认为utf-8
				if (response.getEntity().getContentType().getValue()
						.contains("charset=")) {
					String contentType = response.getEntity().getContentType()
							.getValue();
					charset = contentType.substring(contentType
							.lastIndexOf("=") + 1);
				}
				
				reader = new InputStreamReader(response.getEntity().getContent(), charset);

				/*
				br = new BufferedReader(new InputStreamReader(response
						.getEntity().getContent(), charset));
				StringBuffer sb = new StringBuffer("");
				String line = null;
				while ((line = br.readLine()) != null) {
					logger.info("reload remote synonym: {}", line);
					sb.append(line)
							.append(System.getProperty("line.separator"));
				}
				reader = new FastStringReader(sb.toString());
				*/
			}
		} catch (IOException e) {
			logger.error("get remote synonym reader {} error!", e, location);
			throw new IllegalArgumentException(
					"IOException while reading remote synonyms file", e);
		} catch (Exception e){
			logger.error("get remote synonym reader {} error!", e, location);
			throw new IllegalArgumentException(
					"IOException while reading remote synonyms file", e);
		}finally {
			try {
				if (br != null) {
					br.close();
				}
			} catch (IOException e) {
				logger.error("failed to close bufferedReader", e);
			}
		}
		return reader;
	}

	@Override
	public boolean isNeedReloadSynonymMap() {
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
			if (response.getStatusLine().getStatusCode() == 200) { // 返回200 才做操作
				if (!response.getLastHeader("Last-Modified").getValue()
						.equalsIgnoreCase(lastModified)
						|| !response.getLastHeader("ETag").getValue()
								.equalsIgnoreCase(eTags)) {

					lastModified = response.getLastHeader("Last-Modified") == null ? null
							: response.getLastHeader("Last-Modified")
									.getValue();
					eTags = response.getLastHeader("ETag") == null ? null
							: response.getLastHeader("ETag").getValue();
					return true;
				}
			} else if (response.getStatusLine().getStatusCode() == 304) {
				return false;
			} else {
				logger.info("remote synonym {} return bad code {}", location,
						response.getStatusLine().getStatusCode());
			}

		} catch (IOException e) {
			logger.error("check need reload remote synonym {} error!", e,
					location);
		} finally {
			try {
				if (response != null) {
					response.close();
				}
			} catch (IOException e) {
				logger.error("failed to close http response", e);
			}
		}
		return false;
	}
}
