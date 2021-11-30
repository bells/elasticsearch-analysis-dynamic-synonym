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

import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeAction;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner.newConfigs;

/**
 * Create by guanquan.wang at 2019-09-18 16:55
 */
public class DynamicSynonymPluginTest {
    private ElasticsearchClusterRunner runner;

    @Before
    public void setUp() {
        // create runner instance
        runner = new ElasticsearchClusterRunner();
        // create ES nodes
        runner.build(newConfigs()
                .numOfNode(1) // Create a test node, default number of node is 3.
                .pluginTypes("com.bellszhu.elasticsearch.plugin.DynamicSynonymPlugin")
        );
    }

    @After
    public void tearDown() throws IOException {
        // close runner
        runner.close();
        // delete all files
        runner.clean();
    }

    private void createIndexWithLocalSynonym(String indexName, String synonymType, String localPath) {
        final String indexSettings = "{\n" +
            "  \"index\":{\n" +
            "    \"analysis\":{\n" +
            "      \"filter\":{\n" +
            "        \"local_synonym\": {\n" +
            "            \"type\": \"" + synonymType + "\",\n" +
            "            \"synonyms_path\": \"" + localPath + "\",\n" +
            "            \"interval\": \"10\"\n" +
            "        }"+
            "      },\n" +
            "      \"char_filter\":{\n" +
            "        \"my_char_filter\":{\n" +
            "          \"pattern\":\"[- /]\",\n" +
            "          \"type\":\"pattern_replace\",\n" +
            "          \"replacement\":\"\"\n" +
            "        }\n" +
            "      },\n" +
            "      \"analyzer\":{\n" +
            "        \"synonym_analyzer\":{\n" +
            "          \"filter\":[\n" +
            "            \"lowercase\",\n" +
            "            \"asciifolding\",\n" +
            "            \"local_synonym\"\n" +
            "          ],\n" +
            "          \"type\":\"custom\",\n" +
            "          \"tokenizer\":\"keyword\"\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        runner.createIndex(indexName, Settings.builder().loadFromSource(indexSettings, XContentType.JSON).build());
        // wait for yellow status
        runner.ensureYellow();
    }

    private void createIndexWithRemoteSynonym(String indexName) {
        final String indexSettings = "{\n" +
            "  \"index\":{\n" +
            "    \"analysis\":{\n" +
            "      \"filter\":{\n" +
            "        \"remote_synonym\": {\n" +
            "            \"type\": \"dynamic_synonym\",\n" +
            "            \"synonyms_path\": \"http://localhost:8080/api/synonym\",\n" +
            "            \"interval\": \"10\"\n" +
            "        }"+
            "      },\n" +
            "      \"char_filter\":{\n" +
            "        \"my_char_filter\":{\n" +
            "          \"pattern\":\"[- /]\",\n" +
            "          \"type\":\"pattern_replace\",\n" +
            "          \"replacement\":\"\"\n" +
            "        }\n" +
            "      },\n" +
            "      \"analyzer\":{\n" +
            "        \"synonym_analyzer\":{\n" +
            "          \"filter\":[\n" +
            "            \"lowercase\",\n" +
            "            \"asciifolding\",\n" +
            "            \"remote_synonym\"\n" +
            "          ],\n" +
            "          \"type\":\"custom\",\n" +
            "          \"tokenizer\":\"keyword\"\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        runner.createIndex(indexName, Settings.builder().loadFromSource(indexSettings, XContentType.JSON).build());
        // wait for yellow status
        runner.ensureYellow();
    }

    private synchronized void analyzer(String indexName) throws InterruptedException {
        List<AnalyzeAction.AnalyzeToken> tokens = tokens(indexName, "肯德基");
        for (AnalyzeAction.AnalyzeToken token : tokens) {
            System.out.println(token.getTerm() + " => " + token.getType());
        }

        /*
        Wait one minute to modify the synonym file and run again.
         */
        wait(1000 * 60);

        tokens = tokens(indexName, "金拱门");
        for (AnalyzeAction.AnalyzeToken token : tokens) {
            System.out.println(token.getTerm() + " => " + token.getType());
        }

        tokens = tokens(indexName, "america");
        for (AnalyzeAction.AnalyzeToken token : tokens) {
            System.out.println(token.getTerm() + " => " + token.getType());
        }
    }

    private List<AnalyzeAction.AnalyzeToken> tokens(String indexName, String text) {
        AnalyzeAction.Request analyzeRequest = new AnalyzeAction.Request(indexName);
        analyzeRequest.text(text);
        analyzeRequest.analyzer("synonym_analyzer");
        ActionFuture<AnalyzeAction.Response> actionFuture = runner.admin().indices().analyze(analyzeRequest);
        AnalyzeAction.Response response = actionFuture.actionGet(10L, TimeUnit.SECONDS);
        return response.getTokens();
    }

    @Test
    public void testLocalAbsolute() {
        String index = "test_local_absolute";
        String absolutePath = "target/test-classes/synonym.txt";
        // create an index
        createIndexWithLocalSynonym(index, "dynamic_synonym", absolutePath);

        String text = "肯德基";
        List<AnalyzeAction.AnalyzeToken> analyzeTokens = tokens(index, text);
        for (AnalyzeAction.AnalyzeToken token : analyzeTokens) {
            System.out.println(token.getTerm() + " => " + token.getType());
        }

        assert analyzeTokens.size() == 3;
        for (AnalyzeAction.AnalyzeToken token : analyzeTokens) {
            String key = token.getTerm();
            if (text.equalsIgnoreCase(key)) {
                assert token.getType().equalsIgnoreCase("word");
            } else {
                assert token.getType().equalsIgnoreCase("synonym");
            }
        }
    }

    @Test
    public void testGraphLocalAbsolute() {
        String index = "test_local_absolute";
        String absolutePath = "target/test-classes/synonym.txt";
        // create an index
        createIndexWithLocalSynonym(index, "dynamic_synonym_graph", absolutePath);

        String text = "肯德基";
        List<AnalyzeAction.AnalyzeToken> analyzeTokens = tokens(index, text);
        for (AnalyzeAction.AnalyzeToken token : analyzeTokens) {
            System.out.println(token.getTerm() + " => " + token.getType());
        }

        assert analyzeTokens.size() == 3;
        for (AnalyzeAction.AnalyzeToken token : analyzeTokens) {
            String key = token.getTerm();
            if (text.equalsIgnoreCase(key)) {
                assert token.getType().equalsIgnoreCase("word");
            } else {
                assert token.getType().equalsIgnoreCase("synonym");
            }
        }
    }

    @Test
    public void testLocal() {
        String index = "test_local_relative";
        String absolutePath = "synonym.txt";
        // create an index
        createIndexWithLocalSynonym(index, "dynamic_synonym", absolutePath);

        String text = "kfc";
        List<AnalyzeAction.AnalyzeToken> analyzeTokens = tokens(index, text);
        for (AnalyzeAction.AnalyzeToken token : analyzeTokens) {
            System.out.println(token.getTerm() + " => " + token.getType());
        }

        assert analyzeTokens.size() == 3;
        for (AnalyzeAction.AnalyzeToken token : analyzeTokens) {
            String key = token.getTerm();
            if (text.equalsIgnoreCase(key)) {
                assert token.getType().equalsIgnoreCase("word");
            } else {
                assert token.getType().equalsIgnoreCase("synonym");
            }
        }
    }

    @Test
    public void testRemote() throws InterruptedException {
        String index = "test_remote";
        // create an index
        createIndexWithRemoteSynonym(index);

        analyzer(index);
    }
}
