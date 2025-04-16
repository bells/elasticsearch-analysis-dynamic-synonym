package com.bellszhu.elasticsearch.plugin;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeAction;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESTestCase.WithoutSecurityManager;
import org.elasticsearch.xcontent.XContentType;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@WithoutSecurityManager
public class DynamicSynonymFilterTests extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(
                DynamicSynonymPlugin.class // 你的 plugin 类
        );
    }


    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        //Path configPath = getDataPath("my_test_config"); // 对应 src/test/resources/my_test_config
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal, otherSettings))
                //.put(Environment.PATH_HOME_SETTING.getKey(), configPath.toAbsolutePath().toString())
                .build();
    }

    public void testDynamicSynonymAnalyzer() throws Exception {
        // 1. 创建带 dynamic_synonym 的 analyzer
        //Request createIndex = new Request("PUT", "/test-index");
        CreateIndexRequest request = new CreateIndexRequest("test-index");
        /*request.settings(Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
        );*/
        request.settings("""
                {
                    "analysis": {
                      "filter": {
                        "my_filter": {
                          "type": "dynamic_synonym",
                          "lenient": true,
                          "synonyms_path": "synonym.txt"
                        }
                      },
                      "analyzer": {
                        "my_analyzer": {
                          "tokenizer": "standard",
                          "filter": [
                            "lowercase",
                            "my_filter"
                          ]
                        }
                      }
                    }
                  
                }
        """, XContentType.JSON);
        ActionFuture<CreateIndexResponse> createIndexResponseActionFuture = client().admin().indices().create(request);

        System.out.println(createIndexResponseActionFuture);
        createIndexResponseActionFuture.actionGet();

        AnalyzeAction.Request analyzeRequest = new AnalyzeAction.Request("test-index");
        analyzeRequest.analyzer("my_analyzer");
        analyzeRequest.text("金拱门");


        AnalyzeAction.Response response = client().execute(AnalyzeAction.INSTANCE, analyzeRequest).actionGet();

        List<String> tokens = response.getTokens().stream()
                .map(AnalyzeAction.AnalyzeToken::getTerm)
                .collect(Collectors.toList());

        System.out.println("分词结果: " + tokens);

    }
}
