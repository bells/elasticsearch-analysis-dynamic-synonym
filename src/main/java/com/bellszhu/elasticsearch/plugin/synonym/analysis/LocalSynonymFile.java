/**
 *
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.*;
import java.nio.file.Path;

import org.apache.commons.codec.Charsets;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.common.io.FastStringReader;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.env.Environment;


/**
 * @author bellszhu
 *
 */
public class LocalSynonymFile implements SynonymFile {

	public static Logger logger = ESLoggerFactory.getLogger("dynamic-synonym");

	private String format;

	private boolean expand;

	private Analyzer analyzer;

	private Environment env;

	/** Local file path relative to the config directory */
	private String location;

	private Path synonymFilePath;

	private long lastModified;

	public LocalSynonymFile(Environment env, Analyzer analyzer, boolean expand,
							String format, String location) {
		this.analyzer = analyzer;
		this.expand = expand;
		this.format = format;
		this.env = env;
		this.location = location;

		this.synonymFilePath = env.configFile().resolve(location);
		isNeedReloadSynonymMap();
	}

	@Override
	public SynonymMap reloadSynonymMap() {
		try {
			logger.info("start reload local synonym from {}.", location);
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
			throw new IllegalArgumentException(
					"could not reload local synonyms file to build synonyms", e);
		}

	}

	public Reader getReader() {
		try(BufferedReader br = new BufferedReader(new InputStreamReader(
				synonymFilePath.toUri().toURL().openStream(), Charsets.UTF_8))) {
			StringBuffer sb = new StringBuffer();
			String line;
			while ((line = br.readLine()) != null) {
				logger.info("reload local synonym: {}", line);
				sb.append(line).append(System.getProperty("line.separator"));
			}
			return new FastStringReader(sb.toString());
		} catch (IOException e) {
			logger.error("get local synonym reader {} error!", e, location);
			throw new IllegalArgumentException(
					"IOException while reading local synonyms file", e);
		}
	}

	@Override
	public boolean isNeedReloadSynonymMap() {
		try {
			File synonymFile = synonymFilePath.toFile();
			if (synonymFile.exists()
					&& lastModified < synonymFile.lastModified()) {
				lastModified = synonymFile.lastModified();
				return true;
			}
		} catch (Exception e) {
			logger.error("check need reload local synonym {} error!", e,
					location);
		}

		return false;
	}

}