/**
 * 
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.Locale;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.env.Environment;

/**
 * @author bells
 *
 */
public class LocalSynonymFile implements SynonymFile {

	private String format;

	private boolean expand;

	private Analyzer analyzer;

	private Environment env;
	
	/** 本地文件路径 相对于config目录 */
	private String location;

	/** 上次更改时间 */
	private long lastModified;

	public LocalSynonymFile(Analyzer analyzer, boolean expand, String format, Environment env, String location) {
		this.analyzer = analyzer;
		this.expand = expand;
		this.format = format;
		this.env = env;
		this.location = location;
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
			throw new ElasticsearchIllegalArgumentException(
					"could not reload local synonyms file", e);
		}

	}

	public Reader getReader() {
		URL fileUrl = env.resolveConfig(location);
		Reader reader = null;
		try {
			reader = new InputStreamReader(fileUrl.openStream(), Charsets.UTF_8);
		} catch (IOException ioe) {
			String message = String.format(Locale.ROOT,
					"IOException while reading %s_path: %s", location,
					ioe.getMessage());
			throw new ElasticsearchIllegalArgumentException(message);
		}

		return reader;
	}

	@Override
	public boolean isNeedReloadSynonymMap() {
		try {
			URL synonymFileURL = new URL(location);
			File synonymFile = new File(synonymFileURL.toURI());
			if (synonymFile.exists()
					&& lastModified < synonymFile.lastModified()) {
				lastModified = synonymFile.lastModified();
				return true;
			}
		} catch (Exception e) {
			throw new ElasticsearchIllegalArgumentException(
					"could not reload local synonyms file", e);
		}

		return false;
	}

}
