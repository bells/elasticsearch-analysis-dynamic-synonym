package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import org.apache.lucene.analysis.synonym.SynonymMap;

public interface SynonymFile extends AutoCloseable {
    SynonymMap reloadSynonymMap() throws IOException;

    boolean isNeedReloadSynonymMap() throws IOException;

    @Override
    default void close() throws IOException {
    }
}
