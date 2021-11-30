/**
 *
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.Reader;

import org.apache.lucene.analysis.synonym.SynonymMap;

/**
 * @author bellszhu
 */
public interface SynonymFile {

    SynonymMap reloadSynonymMap();

    boolean isNeedReloadSynonymMap();

    Reader getReader();

}