/**
 * 
 */
package com.bellszhu.elasticsearch.cfg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.InvalidPropertiesFormatException;
import java.util.Properties;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.env.Environment;

public class Configuration {

	public static final String DEFAULT_CONFIG_PATH = "dynamic_synonym/synonym.cfg.xml";
	private static final String SYNONYMS_PATH = "synonyms_path";
	private static final String IGNORECASE = "ignore_case";
	private static final String FORMAT = "format";
	private static final String INTERVAL = "interval";
	private static final String EXPAND = "expand";
	
	private static ESLogger logger = null;
	private Properties props;
	private Environment environment;

	public Configuration(Environment env, String configPath) {
		logger = Loggers.getLogger("dynamic-synonym");
		props = new Properties();
		environment = env;

		if (configPath == null) {
			configPath = DEFAULT_CONFIG_PATH;
		}
		File fileConfig = new File(environment.configFile(), configPath);

		InputStream input = null;
		try {
			input = new FileInputStream(fileConfig);
		} catch (FileNotFoundException e) {
			logger.error("dynamic-synonym", e);
		}
		if (input != null) {
			try {
				props.loadFromXML(input);
			} catch (InvalidPropertiesFormatException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public String getSynonymsPath() {
		String synonymUrl = props.getProperty(SYNONYMS_PATH);
		return synonymUrl;
	}
	
	public boolean getIgnorecase() {
		boolean ignoreCase = Boolean.valueOf(props.getProperty(IGNORECASE, "false"));
		return ignoreCase;
	}
	
	public boolean getExpand() {
		boolean expand = Boolean.valueOf(props.getProperty(EXPAND, "false"));
		return expand;
	}
	
	public int getInterval() {
		int interval = Integer.valueOf(props.getProperty(INTERVAL, "60"));
		return interval;
	}
	
	public String getFormat() {
		String format = props.getProperty(FORMAT, "");
		return format;
	}
}