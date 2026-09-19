package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import static com.bellszhu.elasticsearch.plugin.synonym.analysis.SynonymTestSupport.*;
import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.core5.util.Timeout;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RemoteSynonymFileTest {
    private HttpServer server;
    private ExecutorService executor;
    private Analyzer parser;
    private RemoteSynonymFile file;
    private final AtomicReference<String> rules = new AtomicReference<>("a => old");
    private volatile String tag = "v1";
    private volatile String modified = "Mon, 01 Jan 2024 00:00:00 GMT";
    private volatile int headStatus = 200;
    private volatile int getStatus = 200;
    private volatile String conditionalTag;
    private volatile String conditionalModified;
    private volatile CountDownLatch blocked;
    private final CountDownLatch entered = new CountDownLatch(1);

    @Before
    public void start() throws Exception {
        parser = new WhitespaceAnalyzer();
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/rules", exchange -> {
            try {
                boolean head = exchange.getRequestMethod().equals("HEAD");
                if (tag != null) exchange.getResponseHeaders().set("ETag", tag);
                if (modified != null) exchange.getResponseHeaders().set("Last-Modified", modified);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=\"UTF-8\"; version=1");
                if (head) {
                    conditionalTag = exchange.getRequestHeaders().getFirst("If-None-Match");
                    conditionalModified = exchange.getRequestHeaders().getFirst("If-Modified-Since");
                    exchange.sendResponseHeaders(headStatus, -1);
                } else {
                    CountDownLatch latch = blocked;
                    if (latch != null) { entered.countDown(); latch.await(5, TimeUnit.SECONDS); }
                    byte[] content = rules.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(getStatus, content.length);
                    exchange.getResponseBody().write(content);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        file = source(Timeout.ofSeconds(2));
    }

    private RemoteSynonymFile source(Timeout timeout) {
        return new RemoteSynonymFile(parser, true, false, "",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/rules", timeout);
    }

    @After
    public void stop() throws Exception {
        if (blocked != null) blocked.countDown();
        if (file != null) file.close();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        if (parser != null) parser.close();
    }

    @Test
    public void validatorsCommitFromSuccessfulGetAnd304SkipsReload() throws Exception {
        file.reloadSynonymMap();
        headStatus = 304;
        assertFalse(file.isNeedReloadSynonymMap());
        assertEquals("v1", conditionalTag);
        assertEquals(modified, conditionalModified);
        headStatus = 200;
        tag = "v2";
        assertTrue(file.isNeedReloadSynonymMap());
        // The resource may change again between HEAD and GET.
        tag = "v3";
        rules.set("a => 新版本");
        SynonymMap loaded = file.reloadSynonymMap();
        assertFalse(file.isNeedReloadSynonymMap());
        assertEquals("v3", conditionalTag);
        try (Analyzer analyzer = analyzer(true, () -> loaded)) { assertEquals(List.of("新版本"), terms(analyzer, "a")); }
    }

    @Test
    public void failedGetAndInvalidRulesRetryWithoutCommittingValidators() throws Exception {
        file.reloadSynonymMap();
        tag = "v2";
        assertTrue(file.isNeedReloadSynonymMap());
        getStatus = 503;
        assertThrows(IOException.class, file::reloadSynonymMap);
        headStatus = 304;
        assertTrue(file.isNeedReloadSynonymMap());
        getStatus = 200;
        rules.set("a => b => c");
        assertThrows(IOException.class, file::reloadSynonymMap);
        assertTrue(file.isNeedReloadSynonymMap());
        rules.set("a => recovered");
        file.reloadSynonymMap();
        headStatus = 200;
        assertFalse(file.isNeedReloadSynonymMap());
    }

    @Test
    public void missingValidatorsHeadFallbackAndServerErrors() throws Exception {
        file.reloadSynonymMap();
        tag = null;
        modified = null;
        assertTrue(file.isNeedReloadSynonymMap());
        file.reloadSynonymMap();
        assertTrue(file.isNeedReloadSynonymMap());
        headStatus = 405;
        assertTrue(file.isNeedReloadSynonymMap());
        headStatus = 501;
        assertTrue(file.isNeedReloadSynonymMap());
        headStatus = 500;
        assertThrows(IOException.class, file::isNeedReloadSynonymMap);
        rules.set("");
        assertNull(file.reloadSynonymMap().fst);
    }

    @Test
    public void timeoutRetainsRetryState() throws Exception {
        file.close();
        file = source(Timeout.ofMilliseconds(100));
        blocked = new CountDownLatch(1);
        assertThrows(IOException.class, file::reloadSynonymMap);
        assertTrue(file.isNeedReloadSynonymMap());
        blocked.countDown();
        blocked = null;
        file.reloadSynonymMap();
        assertFalse(file.isNeedReloadSynonymMap());
    }

    @Test
    public void closeCancelsInFlightRequest() throws Exception {
        blocked = new CountDownLatch(1);
        Future<?> pending = executor.submit(() -> assertThrows(IOException.class, file::reloadSynonymMap));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        file.close();
        pending.get(2, TimeUnit.SECONDS);
        file.close();
    }
}
