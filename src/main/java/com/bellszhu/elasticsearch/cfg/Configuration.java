/**
 * 
 */
package com.bellszhu.elasticsearch.cfg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.InvalidPropertiesFormatException;
import java.util.List;
import java.util.Properties;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.env.Environment;

public class Configuration {

	private static String FILE_NAME = "dynamic_synonym/synonym.cfg.xml";
	private static final String REMOTE_SYNONYM = "remote_synonym";
	private static ESLogger logger = null;
	private Properties props;
	private Environment environment;

	public Configuration(Environment env) {
		logger = Loggers.getLogger("dynamic-synonym");
		props = new Properties();
		environment = env;

		File fileConfig = new File(environment.configFile(), FILE_NAME);

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

	public List<String> getRemoteExtDictionarys() {
		List<String> remoteExtDictFiles = new ArrayList<String>(2);
		String remoteExtDictCfg = props.getProperty(REMOTE_SYNONYM);
		if (remoteExtDictCfg != null) {

			String[] filePaths = remoteExtDictCfg.split(";");
			if (filePaths != null) {
				for (String filePath : filePaths) {
					if (filePath != null && !"".equals(filePath.trim())) {
						remoteExtDictFiles.add(filePath);

					}
				}
			}
		}
		return remoteExtDictFiles;
	}

	public File getDictRoot() {
		return environment.configFile();
	}
}
