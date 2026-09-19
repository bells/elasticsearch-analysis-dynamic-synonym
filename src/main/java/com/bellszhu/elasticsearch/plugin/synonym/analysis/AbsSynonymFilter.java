package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymMap;

/** A stream pins one immutable synonym snapshot until its next reset. */
public abstract class AbsSynonymFilter extends TokenFilter {
    private final Supplier<SynonymMap> snapshots;
    private SynonymMap current;
    private TokenStream delegate;

    protected AbsSynonymFilter(TokenStream input, Supplier<SynonymMap> snapshots) {
        super(input);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    protected abstract TokenStream createDelegate(SynonymMap map);

    @Override
    public final void reset() throws IOException {
        SynonymMap next = Objects.requireNonNull(snapshots.get());
        if (delegate == null || next != current) {
            // Delegates share input/attributes. Closing an old delegate here would close input.
            delegate = next.fst == null ? input : createDelegate(next);
            current = next;
        }
        delegate.reset();
    }

    @Override
    public final boolean incrementToken() throws IOException {
        return delegate.incrementToken();
    }

    @Override
    public final void end() throws IOException {
        delegate.end();
    }

    @Override
    public final void close() throws IOException {
        if (delegate == null) {
            input.close();
        } else {
            delegate.close();
        }
    }
}
