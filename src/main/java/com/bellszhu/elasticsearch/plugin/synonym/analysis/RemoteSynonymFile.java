package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.util.TimeValue;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.env.Environment;

/** @author bellszhu */
public class RemoteSynonymFile implements SynonymFile {
    private final CloseableHttpClient client;
    private final Analyzer analyzer;
    private final String location;
    private final String format;
    private final boolean expand;
    private final boolean lenient;
    private final Timeout responseTimeout;
    private String lastModified;
    private String etag;
    private boolean retry = true;
    private final AtomicBoolean closed = new AtomicBoolean();

    RemoteSynonymFile(Environment env, Analyzer analyzer, boolean expand, boolean lenient,
                      String format, String location) {
        this(analyzer, expand, lenient, format, location, Timeout.ofSeconds(60));
    }

    RemoteSynonymFile(Analyzer analyzer, boolean expand, boolean lenient,
                      String format, String location, Timeout responseTimeout) {
        this.analyzer = analyzer;
        this.expand = expand;
        this.lenient = lenient;
        this.format = format;
        this.location = location;
        this.responseTimeout = responseTimeout;
        this.client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(10, TimeUnit.SECONDS)
                    .setSocketTimeout(responseTimeout).build()).build())
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(10, TimeUnit.SECONDS)
                .setResponseTimeout(responseTimeout).build())
            .setRetryStrategy(new DefaultHttpRequestRetryStrategy(1, TimeValue.ofMilliseconds(0)) {
                @Override
                public TimeValue getRetryInterval(HttpResponse response, int execCount, HttpContext context) {
                    // Do not let an arbitrary Retry-After header stall this factory's other chains.
                    return TimeValue.ofMilliseconds(0);
                }
            })
            .build();
    }

    private static String header(ClassicHttpResponse response, String name) {
        Header value = response.getLastHeader(name);
        return value == null ? null : value.getValue();
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("Synonym source is closed");
        }
    }

    private <T> T execute(ClassicHttpRequest request, HttpClientResponseHandler<T> handler) throws IOException {
        ensureOpen();
        try {
            return client.execute(request, handler);
        } catch (IllegalStateException e) {
            if (closed.get()) {
                throw new IOException("Synonym source closed during HTTP request", e);
            }
            throw e;
        }
    }

    @Override
    public synchronized boolean isNeedReloadSynonymMap() throws IOException {
        ensureOpen();
        if (retry) {
            return true;
        }
        HttpHead request = new HttpHead(location);
        request.setConfig(RequestConfig.custom()
            .setConnectionRequestTimeout(10, TimeUnit.SECONDS)
            .setResponseTimeout(Timeout.ofMilliseconds(Math.min(15000, responseTimeout.toMilliseconds())))
            .build());
        if (lastModified != null) {
            request.setHeader("If-Modified-Since", lastModified);
        }
        if (etag != null) {
            request.setHeader("If-None-Match", etag);
        }
        return execute(request, response -> {
            int status = response.getCode();
            if (status == 304) {
                return false;
            }
            // Some static endpoints only implement GET.
            if (status == 405 || status == 501) {
                return true;
            }
            if (status != 200) {
                throw new IOException("Synonym HEAD returned HTTP " + status);
            }
            String modified = header(response, "Last-Modified");
            String tag = header(response, "ETag");
            return (modified == null && tag == null)
                || !Objects.equals(modified, lastModified) || !Objects.equals(tag, etag);
        });
    }

    @Override
    public synchronized SynonymMap reloadSynonymMap() throws IOException {
        retry = true;
        Loaded loaded = execute(new HttpGet(location), response -> {
            if (response.getCode() != 200) {
                throw new IOException("Synonym GET returned HTTP " + response.getCode());
            }
            SynonymMap map;
            if (response.getEntity() == null) {
                map = SynonymRuleParser.parse(new StringReader(""), format, expand, lenient, analyzer);
            } else {
                Charset charset = StandardCharsets.UTF_8;
                String contentType = response.getEntity().getContentType();
                if (contentType != null) {
                    Charset declared = ContentType.parse(contentType).getCharset();
                    if (declared != null) {
                        charset = declared;
                    }
                }
                try (Reader reader = new InputStreamReader(response.getEntity().getContent(), charset)) {
                    map = SynonymRuleParser.parse(reader, format, expand, lenient, analyzer);
                }
            }
            return new Loaded(map, header(response, "Last-Modified"), header(response, "ETag"));
        });
        // Commit validators from the same GET only after the complete response parsed successfully.
        lastModified = loaded.modified();
        etag = loaded.etag();
        retry = false;
        return loaded.map();
    }

    @Override
    public void close() throws IOException {
        // May be called while a poll is blocked in HTTP; closing the client cancels that I/O.
        if (closed.compareAndSet(false, true)) {
            client.close();
        }
    }

    private record Loaded(SynonymMap map, String modified, String etag) {
    }
}
