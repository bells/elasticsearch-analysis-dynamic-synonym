# Elasticsearch 原生能力与插件定位

更新日期：2026-09-25。依据为 Elastic 官方文档、9.3.4/9.5.4 Maven API 和本地构建；具体运行证据见 [项目审查](PROJECT_REVIEW.md)。

## 结论

原生 Elasticsearch 已支持搜索同义词热更新。本插件不再是实现这项能力的必需组件；新项目优先采用原生 `synonym_graph` 与 Synonyms API。它仍适用于已有 HTTP(S) 词库服务、需要节点自动轮询外部词库或本地文件的部署。

| ES 版本 | 原生能力 | 是否需要本插件 |
| --- | --- | --- |
| 8.0–8.9（含本项目 8.7.1） | `synonym` / `synonym_graph` 文件词库，`updateable: true` 的搜索分析器可以调用 `_reload_search_analyzers` 重载；该 API 自 7.3.0 提供 | 单纯重载不需要。直接拉取 HTTP(S) 或免手动调用 API 的轮询有额外价值 |
| 8.10+ | Synonyms Management API（`PUT /_synonyms/{id}`）管理集群同义词集，更新已有集合自动重载相关搜索分析器；保留文件型方案 | 原生方案可覆盖大多数搜索时同义词管理需求；插件主要服务既有外部词库集成 |
| 9.x | 继续支持原生同义词集、自动重载和文件型搜索分析器重载。当前明确构建版本为 9.3.4、9.5.4 | 通常优先原生方案；本插件服务直接 HTTP(S) 与本地文件轮询 |

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

当前源码以 **ES 9+ / Java 21** 为主，默认构建 9.5.4，另支持 `-Drevision=9.3.4`。两份经典插件 ZIP 各自只匹配对应宿主版本；不宣称 9.3.4 ZIP 可以装入 9.5.4，也不宣称未列出的 9.x 已通过验证。8.7.1 等旧版使用历史 release 包。新增 9.x 版本时需要重新编译、核对 ES/Lucene API、描述符、JDK、权限模型、依赖和真实节点运行。

## Stable Plugin API 评估

[官方稳定插件文档](https://www.elastic.co/docs/extend/elasticsearch/creating-stable-plugins)说明，稳定分析插件在同一 ES 大版本内升级到后续小版本或补丁版本时可以复用已有构建。它提供 `@NamedComponent`、`AnalysisSettings` 和 `org.elasticsearch.plugin.analysis.TokenFilterFactory` 等接口；后者在 9.5.4 中提供 `create(TokenStream)`、`normalize`、`getAnalysisMode`，没有当前经典插件使用的链特化入口。

| 当前依赖的能力 | 稳定 API 评估 |
| --- | --- |
| 使用当前 tokenizer、char filters、前置 filters 构建规则解析 Analyzer | 稳定 `TokenFilterFactory` 未暴露 `getChainAwareTokenFilterFactory`、前置链和 `getSynonymFilter`；直接迁移会改变规则解析语义 |
| 按索引移除释放轮询任务、HTTP 客户端和解析 Analyzer | 稳定分析工厂接口未暴露 `IndexEventListener.afterIndexRemoved`；需重新设计资源所有权与释放时机 |
| 从 ES config 目录读取本地词库 | 当前调用经典插件的 `Environment.configDir()`；稳定接口没有这一 `Environment` 参数，需另行确定安全路径契约 |

因此当前 9.x 适配继续使用经典插件 API 和精确版本构建。将来评估稳定 API，应先设计可验证的分析链、配置目录与生命周期替代方案，再检查空词库、失败保留、graph 位置和实际节点卸载行为。单纯改依赖或描述符无法保持现有行为，也不能跨 8.x→9.x 复用产物。

如果主要需求只是把外部 HTTP 词库同步到 ES，独立同步服务调用 8.10+ Synonyms Management API 可减少节点插件维护；迁移前仍需核对词库格式、搜索分析链和已有索引设置。

已有部署迁移原生方案时：先核对搜索/索引用途和词库格式，将外部规则写入原生集合，使用 `_analyze` 对比词项、位置和多词查询结果，在测试索引上验收后切换搜索分析器。不能把现有 index settings 中的 filter 名直接替换并假定在线生效，也不能未经验证直接卸载正被索引引用的插件。

## 官方来源

- [Reload search analyzers API（自 7.3.0）](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-indices-reload-search-analyzers)
- [Create or update a synonym set（自 8.10.0；自动重载）](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-synonyms-put-synonym)
- [Synonym graph token filter](https://www.elastic.co/docs/reference/text-analysis/analysis-synonym-graph-tokenfilter)
- [Elasticsearch v9.5.4 官方发布](https://github.com/elastic/elasticsearch/releases/tag/v9.5.4)
