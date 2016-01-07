/**
 * 
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.env.Environment;

/**
 * @author bells
 *
 */
public class LocalSynonymFile implements SynonymFile {
	
	public static ESLogger logger = Loggers.getLogger("dynamic-synonym");

	private String format;

	private boolean expand;

	private Analyzer analyzer;

	private Environment env;
	
	/** 本地文件路径 相对于config目录 */
	private String location;
	
	private URL synonymFileURL;

	/** 上次更改时间 */
	private long lastModified;

	public LocalSynonymFile(Environment env, Analyzer analyzer, boolean expand, String format, String location) {
		this.analyzer = analyzer;
		this.expand = expand;
		this.format = format;
		this.env = env;
		this.location = location;
		
		synonymFileURL = env.resolveConfig(location);
	}

	@Override
	public SynonymMap reloadSynonymMap() {
		try {
			Reader rulesReader = getReader();
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
			logger.error("reload local synonym {} error!", e, location);
			throw new ElasticsearchIllegalArgumentException(
					"could not reload local synonyms file", e);
		}

	}

	public Reader getReader() {
		Reader reader = null;
		try {
			reader = new InputStreamReader(synonymFileURL.openStream(), Charsets.UTF_8);
		} catch (IOException e) {
			logger.error("get local synonym reader {} error!", e, location);
			throw new ElasticsearchIllegalArgumentException(
					"IOException while reading local synonyms file", e);
		}

		return reader;
	}

	@Override
	public boolean isNeedReloadSynonymMap() {
		try {
			File synonymFile = new File(synonymFileURL.toURI());
			if (synonymFile.exists()
					&& lastModified < synonymFile.lastModified()) {
				lastModified = synonymFile.lastModified();
				return true;
			}
		} catch (Exception e) {
			logger.error("check need reload local synonym {} error!", e, location);
		}

		return false;
	}

}
