/**
 * 
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import org.apache.lucene.analysis.synonym.SynonymMap;

/**
 * @author bells
 *
 */
public interface SynonymFile {
	
	public SynonymMap createSynonymMap();

}
