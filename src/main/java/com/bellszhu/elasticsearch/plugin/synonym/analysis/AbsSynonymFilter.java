package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymMap;

/**
 * @author bellszhu
 */
public abstract class AbsSynonymFilter extends TokenFilter {
    /**
     * Construct a token stream filtering the given input.
     *
     * @param input
     */
    protected AbsSynonymFilter(TokenStream input) {
        super(input);
    }

    abstract void update(SynonymMap synonymMap);
}
