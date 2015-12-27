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

	private String filePath;

	private String format;

	private boolean expand;

	private Analyzer analyzer;

	private Environment env;
	
	/** 上次更改时间 */
	private long lastModified;
	
	/** 请求地址 */
	private String location;
	
	/*
	public void run() {
		try {
			URL synonymFileURL = new URL(location);
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
			throw new ElasticsearchIllegalArgumentException(
					"could not reload local synonyms file", e);
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

		
		// lastModified = synonymFile.lastModified();

	}

	private Reader getReader() {

		if (filePath == null) {
			return null;
		}

		URL fileUrl = env.resolveConfig(filePath);

		Reader reader = null;
		try {
			reader = new InputStreamReader(fileUrl.openStream(), Charsets.UTF_8);
		} catch (IOException ioe) {
			String message = String.format(Locale.ROOT,
					"IOException while reading %s_path: %s", filePath,
					ioe.getMessage());
			throw new ElasticsearchIllegalArgumentException(message);
		}

		return reader;
	}

}
