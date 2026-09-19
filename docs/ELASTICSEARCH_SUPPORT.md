# Elasticsearch 原生能力与插件定位

核实日期：2026-09-19。依据为 Elastic 官方文档/API 定义及官方 GitHub 发布记录；本次插件运行验证针对 8.7.1，不代表已验证其他版本。

## 结论

原生 Elasticsearch 已支持搜索同义词热更新。本插件不再是实现这项能力的必需组件；新项目优先采用原生 `synonym_graph` 与 Synonyms API。它仍适用于已有 HTTP(S) 词库服务、需要节点自动轮询外部词库或本地文件的部署。

| ES 版本 | 原生能力 | 是否需要本插件 |
| --- | --- | --- |
| 8.0–8.9（含本项目 8.7.1） | `synonym` / `synonym_graph` 文件词库，`updateable: true` 的搜索分析器可以调用 `_reload_search_analyzers` 重载；该 API 自 7.3.0 提供 | 单纯重载不需要。直接拉取 HTTP(S) 或免手动调用 API 的轮询有额外价值 |
| 8.10+ | Synonyms Management API（`PUT /_synonyms/{id}`）管理集群同义词集，更新已有集合自动重载相关搜索分析器；保留文件型方案 | 原生方案可覆盖大多数搜索时同义词管理需求；插件主要服务既有外部词库集成 |
| 9.x | 继续支持原生同义词集、自动重载和文件型搜索分析器重载。核实时官方最新 release 为 9.5.4（2026-09-15） | 通常优先原生方案；当前插件不宣称支持 9.x |

`updateable` 的拼写是 Elasticsearch 契约。它限制为搜索分析器使用，不是“重写已索引文档”的开关。索引时同义词变化只影响后续写入；已有文档需要重建索引才能一致。插件也无法突破这一限制。

## 原生方案示例（8.10+）

先建立同义词集，再建立引用它的索引。原生集合格式使用 Solr 风格规则：

```http
PUT /_synonyms/product-synonyms
{
  "synonyms_set": [
    { "id": "notebook", "synonyms": "notebook, laptop" }
  ]
}
```

```http
PUT /products
{
  "settings": {
    "analysis": {
      "filter": {
        "product_synonyms": {
          "type": "synonym_graph",
          "synonyms_set": "product-synonyms",
          "updateable": true,
          "lenient": false
        }
      },
      "analyzer": {
        "product_search": {
          "tokenizer": "standard",
          "filter": ["lowercase", "product_synonyms"]
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "standard",
        "search_analyzer": "product_search"
      }
    }
  }
}
```

再次更新 `product-synonyms` 后，ES 自动重载使用该集合的搜索分析器，不需要插件定时器。示例来自官方接口契约整理，本次未在 8.10 或 9.x 集群执行。

8.7.1 使用原生文件方案时，先更新所有 data node 上的文件，包括暂未持有目标分片的节点，然后调用：

```http
POST /products/_reload_search_analyzers
POST /products/_cache/clear?request=true
```

这要求搜索分析链中的原生同义词 filter 配置 `synonyms_path`、`updateable: true`，且不用于索引分析器。官方文档要求重载后清理 request cache。

## 本插件保留的差异

- 直接读取 HTTP(S) 词库，定时 HEAD 检查、GET 下载；不要求另行把外部词库写入 Synonyms API。
- 自动轮询本地文件，免去调用重载 API；每个节点仍需要可读取同一版本的词库。
- 维持已有 `dynamic_synonym` / `dynamic_synonym_graph` 配置和 Solr/WordNet 文件接入方式。

代价是安装和维护版本匹配的插件，以及节点间异步轮询带来的短暂版本差异。本次修复保证一个 TokenStream 周期内使用稳定快照，不提供整个集群同时切换的事务保证，也不会自动清理 Elasticsearch request cache。对请求缓存敏感的场景应在词库更新后清理对应缓存，或采用原生更新流程。

## 版本与迁移边界

当前产物只针对 **ES 8.7.1 / Java 17**，不能把同一个 ZIP 装到其他 8.x 或 9.x。不同版本需要重新编译、核对 ES/Lucene API、插件描述符、JDK、安全/权限模型、打包依赖及真实节点运行。9.x 的适配不属于本次风险修复。

已有部署迁移原生方案时：先核对搜索/索引用途和词库格式，将外部规则写入原生集合，使用 `_analyze` 对比词项、位置和多词查询结果，在测试索引上验收后切换搜索分析器。不能把现有 index settings 中的 filter 名直接替换并假定在线生效，也不能未经验证直接卸载正被索引引用的插件。

## 官方来源

- [Reload search analyzers API（自 7.3.0）](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-indices-reload-search-analyzers)
- [Create or update a synonym set（自 8.10.0；自动重载）](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-synonyms-put-synonym)
- [Synonym graph token filter](https://www.elastic.co/docs/reference/text-analysis/analysis-synonym-graph-tokenfilter)
- [Elasticsearch v9.5.4 官方发布](https://github.com/elastic/elasticsearch/releases/tag/v9.5.4)
