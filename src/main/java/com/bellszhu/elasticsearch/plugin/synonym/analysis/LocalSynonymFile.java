/**
 *
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import org.apache.commons.codec.Charsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.env.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;


/**
 * @author bellszhu
 */
public class LocalSynonymFile implements SynonymFile {

    private static Logger logger = LogManager.getLogger("dynamic-synonym");

    private String format;

    private boolean expand;

    private boolean lenient;

    private Analyzer analyzer;

    private Environment env;

    /**
     * Local file path relative to the config directory
     */
    private String location;

    private Path synonymFilePath;

    private long lastModified;

    LocalSynonymFile(Environment env, Analyzer analyzer, boolean expand, boolean lenient,
                     String format, String location) {
        this.analyzer = analyzer;
        this.expand = expand;
        this.lenient = lenient;
        this.format = format;
        this.env = env;
        this.location = location;

        this.synonymFilePath = deepSearch();
        isNeedReloadSynonymMap();
    }

    @Override
    public SynonymMap reloadSynonymMap() {
        try {
            logger.info("start reload local synonym from {}.", synonymFilePath);
            Reader rulesReader = getReader();
            SynonymMap.Builder parser = RemoteSynonymFile.getSynonymParser(
                    rulesReader, format, expand, lenient, analyzer);
            return parser.build();
        } catch (Exception e) {
            logger.error("reload local synonym {} error!", synonymFilePath, e);
            throw new IllegalArgumentException(
                    "could not reload local synonyms file to build synonyms", e);
        }

    }

    /*
    Just deleted when reading the file, Returns empty synonym
      keyword if file not exists.
    A small probability event.
    */
    public Reader getReader() {
        if (!Files.exists(synonymFilePath)) {
            return new StringReader("");
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                synonymFilePath.toUri().toURL().openStream(), Charsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                // logger.info("reload local synonym: {}", line);
                sb.append(line).append(System.getProperty("line.separator"));
            }
            return new StringReader(sb.toString());
        } catch (IOException e) {
            logger.error("get local synonym reader {} error!", location, e);
//            throw new IllegalArgumentException(
//                    "IOException while reading local synonyms file", e);
//            Fix #54 Returns blank if synonym file has be deleted.
            return new StringReader("");
        }
    }

    @Override
    public boolean isNeedReloadSynonymMap() {
        try {
            /*
            If the file does not exist, it will be scanned every time
              until the file is restored.
             */
            if (!Files.exists(synonymFilePath) && !Files.exists(synonymFilePath = deepSearch())) {
                return false;
            }
            File synonymFile = synonymFilePath.toFile();
            if (synonymFile.exists()
                    && lastModified < synonymFile.lastModified()) {
                lastModified = synonymFile.lastModified();
                return true;
            }
        } catch (Exception e) {
            logger.error("check need reload local synonym {} error!", location, e);
        }

        return false;
    }

    /**
     * Deep search synonym file.
     * Step 1. Query the 'sysnonym_path' parameter as an absolute path
     * Step 2. Query the es config path
     * Step 3. Query in current relative path
     * <p>
     * Override this method to expend search path
     *
     * @return the synonym path.
     */
    protected Path deepSearch() {
        return env.configFile().resolve(location);
//        // TODO
//        SpecialPermission.check();
//        return AccessController.doPrivileged((PrivilegedAction<Path>) () -> {
//            return env.configFile().resolve(location);
////            // access denied：java.io.FilePermission
////            Path path;
////            // Load setting config as absolute path
////            if (Files.exists(Paths.get(location))) { // access denied：java.io.FilePermission
////                path = Paths.get(location);
////                // Load from setting config path
////            } else if (Files.exists(env.configFile().resolve(location))) {
////                path = env.configFile().resolve(location);
////                // Load from current relative path
////            } else {
////                URL url = getClass().getClassLoader().getResource(location);
////                if (url != null) {
////                    path = Paths.get(url.getFile());
////                } else {
////                    path = env.configFile().resolve(location);
////                }
////            }
////            return path;
//        });
    }
}