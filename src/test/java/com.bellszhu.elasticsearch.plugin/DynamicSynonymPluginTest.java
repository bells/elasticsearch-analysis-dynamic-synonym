/*
 * Copyright (c) 2019, guanquan.wang@yandex.com All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bellszhu.elasticsearch.plugin;

import static org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner.newConfigs;
import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeAction;
import org.elasticsearch.common.settings.Settings;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class DynamicSynonymPluginTest {
    private static ElasticsearchClusterRunner runner;
    private static final AtomicInteger IDS = new AtomicInteger();

    @BeforeClass
    public static void setUp() {
        runner = new ElasticsearchClusterRunner();
        runner.build(newConfigs().numOfNode(1).pluginTypes(DynamicSynonymPlugin.class.getName()));
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (runner != null) {
            runner.close();
            runner.clean();
        }
    }

    private String create(String type, String location) {
        String index = "synonym_test_" + IDS.incrementAndGet();
        Settings settings = Settings.builder()
            .put("index.number_of_shards", 1).put("index.number_of_replicas", 0)
            .put("index.analysis.filter.rules.type", type)
            .put("index.analysis.filter.rules.synonyms_path", location)
            .put("index.analysis.filter.rules.interval", 1)
            .put("index.analysis.analyzer.synonym_analyzer.tokenizer", "whitespace")
            .putList("index.analysis.analyzer.synonym_analyzer.filter", "lowercase", "rules").build();
        runner.createIndex(index, settings);
        runner.ensureYellow();
        return index;
    }

    private List<String> terms(String index, String text) {
        AnalyzeAction.Request request = new AnalyzeAction.Request(index).text(text).analyzer("synonym_analyzer");
        return runner.admin().indices().analyze(request).actionGet(10, TimeUnit.SECONDS)
            .getTokens().stream().map(AnalyzeAction.AnalyzeToken::getTerm).toList();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue("Condition did not become true before deadline", condition.getAsBoolean());
    }

    private static long monitors() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.isAlive() && t.getName().startsWith("monitor-synonym-Thread-")).count();
    }

    @Test
    public void relativeLocalReloadAndIndexDeletionStopMonitors() throws Exception {
        Path config = runner.node().getEnvironment().configFile();
        Path rules = Files.createTempFile(config, "synonym-test-", ".txt");
        try {
            for (String type : List.of("dynamic_synonym", "dynamic_synonym_graph")) {
                long before = monitors();
                Files.writeString(rules, "");
                String index = create(type, rules.getFileName().toString());
                try {
                    assertEquals(List.of("a"), terms(index, "A"));
                    Files.writeString(rules, "a => first second");
                    await(() -> terms(index, "A").equals(List.of("first", "second")));
                    Files.writeString(rules, "");
                    await(() -> terms(index, "A").equals(List.of("a")));
                    Files.writeString(rules, "a => restored");
                    await(() -> terms(index, "A").equals(List.of("restored")));
                } finally {
                    runner.deleteIndex(index);
                }
                await(() -> monitors() <= before);
            }
        } finally {
            Files.deleteIfExists(rules);
        }
    }

    @Test
    public void absoluteLocalPathExpandsOriginalFixture() throws Exception {
        String index = create("dynamic_synonym", Paths.get("target/test-classes/synonym.txt").toAbsolutePath().toString());
        try {
            assertEquals(java.util.Set.of("金拱门", "肯德基", "kfc"), new java.util.HashSet<>(terms(index, "肯德基")));
        } finally {
            runner.deleteIndex(index);
        }
    }

    @Test
    public void remoteReloadUsesControlledHttpService() throws Exception {
        AtomicReference<String> rules = new AtomicReference<>("a => initial");
        AtomicInteger status = new AtomicInteger(200);
        AtomicInteger failures = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rules", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    int code = status.get();
                    if (code != 200) failures.incrementAndGet();
                    byte[] body = rules.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(code, body.length);
                    exchange.getResponseBody().write(body);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            for (String type : List.of("dynamic_synonym", "dynamic_synonym_graph")) {
                rules.set("a => initial");
                status.set(200);
                String index = create(type, "http://127.0.0.1:" + server.getAddress().getPort() + "/rules");
                try {
                    assertEquals(List.of("initial"), terms(index, "a"));
                    int before = failures.get();
                    status.set(503);
                    await(() -> failures.get() > before);
                    assertEquals(List.of("initial"), terms(index, "a"));
                    rules.set("a => recovered");
                    status.set(200);
                    await(() -> terms(index, "a").equals(List.of("recovered")));
                } finally {
                    runner.deleteIndex(index);
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
