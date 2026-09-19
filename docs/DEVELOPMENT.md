# 开发与验证

先阅读根 [AGENTS.md](../AGENTS.md)。目标为 Elasticsearch 8.7.1 / Java 17，单模块 Maven 构建，无 Maven Wrapper。关于原生 ES 方案参见 [版本与定位](ELASTICSEARCH_SUPPORT.md)。

## 环境和构建

推荐 JDK 17、Maven 3.9.x、Python 3（交付检查脚本），Docker 用于容器验收。先确认 Maven JVM：

```sh
java -version
mvn -version
# macOS 多 JDK 环境
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
mvn -version
```

在仓库根执行：

```sh
mvn test
mvn clean verify
python3 scripts/verify-package.py
```

缓存齐备时可加 `-o`。Central/CodeLibs 下载失败需检查 Maven 仓库/代理配置；shell 代理变量不保证 JVM 自动使用代理，不提交个人 settings.xml 或凭据。`clean` 删除 target；`-DskipTests package` 只可排查打包，不是功能验证。

## 测试分层

| 测试 | 主要覆盖 |
| --- | --- |
| `SnapshotFilterTest` | 普通/Graph 空词库切换、稳定快照、多词位置与偏移、reset 复用、并发发布/分析 |
| `LocalSynonymFileTest` | 相对/绝对路径、时间回退、删除恢复、失败重试、空文件、WordNet、expand/lenient |
| `RemoteSynonymFileTest` | 临时 HTTP 服务；304、校验头提交、缺失头、405/501、GET 失败/解析失败恢复、超时、关闭取消请求 |
| `DynamicSynonymFactoryTest` | 自动轮询、按分析链隔离 parser、两类 filter 链式使用、大小写、配置校验、初始化/关闭清理 |
| `DynamicSynonymPluginTest` | ES 8.7.1 临时节点；实际 `_analyze`、本地相对路径热更新、HTTP 失败保留/恢复、索引删除后的 monitor 线程退出 |

当前无人工改词库或依赖固定外部服务的跳过测试。集成测试的历史路径仍为 `src/test/java/com.bellszhu.elasticsearch.plugin/`，新增测试使用标准包目录 `src/test/java/com/bellszhu/elasticsearch/plugin/synonym/analysis/`。

测试使用 JUnit 断言，自动创建/清理临时文件和服务。HTTP 服务选择临时端口；ClusterRunner 使用可用本地端口启动单节点。沙箱限制监听时，应在允许监听的环境重跑，不禁用测试制造成功记录。新测试用有截止时间的条件轮询代替人工等待。

## ZIP 与安装验收

```sh
python3 scripts/verify-package.py
unzip -l target/releases/elasticsearch-analysis-dynamic-synonym-8.7.1.zip
unzip -p target/releases/elasticsearch-analysis-dynamic-synonym-8.7.1.zip plugin-descriptor.properties
```

脚本检查描述符生效属性、精确版本、必需文件/依赖、重复条目、禁止打包的宿主/测试依赖及 Docker 版本对齐。注释中的历史占位符不影响加载。根 LICENSE 依据原有 POM 的 Apache 2.0 声明补齐，NOTICE 保留 Lucene 归属，各依赖 JAR 保留自身许可。

完整安装验证在隔离 ES 节点进行。`scripts/smoke-test.py` 创建唯一测试索引，验证两种 filter 的空→非空、非法词库保留、清空及恢复，最后删除测试索引。它要求 `SYNONYM_FILE` 是专用可写测试文件，并映射到目标节点的 `config/synonym-smoke.txt`；不要指向生产词库或生产集群。

```sh
ES_URL=http://127.0.0.1:19200 SYNONYM_FILE=/absolute/test-config/synonym-smoke.txt python3 scripts/smoke-test.py
```

此脚本要求无认证的本地隔离测试节点；不要为运行它关闭生产认证。ClusterRunner 类路径测试与真正容器安装验证要分别报告。

## Docker 与 CI

```sh
make build_image
# 仅预览发布命令，不实际发布
make -n push_image image_host=example/ image_tag=v8.7.1
```

Docker builder 使用 JDK 17，包含全部 src 并执行 `mvn clean verify`；运行镜像 ES 8.7.1。`.github/workflows/verify.yml` 覆盖 PR、分支 push 和可复用调用，执行 Maven、ZIP 检查、镜像构建及上述容器 smoke。需要 Docker daemon；CI 容器限制在 loopback 端口且使用临时词库。

发布 workflow 在 `v*.*.*` 标签 push 时触发，先执行 verify，并要求标签等于 `v` 加 POM 版本。镜像命名空间来自 `DOCKER_USERNAME` secret，凭据为 `DOCKER_PASSWORD`，旧的未定义 `IMAGE_HOST_SLASH_APPENDED` 已移除。

`make push_image` 需要显式 `image_host`，只推送所选标签；不再使用 `docker push -a` 或自动发布 latest。`image_tag` 默认读取 POM，可通过命令行覆盖。只有用户明确要求才执行发布或推送标签。

## CodeGraph

先检查索引，源码变更后同步。已核对 CodeGraph 1.0.1 命令：

```sh
codegraph status
codegraph files
codegraph query DynamicSynonym --limit 12
codegraph node reloadSynonymMap --file src/main/java/com/bellszhu/elasticsearch/plugin/synonym/analysis/RemoteSynonymFile.java
codegraph impact DynamicSynonymTokenFilterFactory --depth 2
codegraph affected src/main/java/com/bellszhu/elasticsearch/plugin/synonym/analysis/DynamicSynonymTokenFilterFactory.java
codegraph sync
```

同名方法使用 `--file` 区分。静态图不完整覆盖 Java 插件注册、配置字符串、运行时分派；`affected` 为空不能据此跳过 Maven 测试。CodeGraph 未索引全部 Docker/文档/词库，配合 `rg --files --hidden -g '!.git/**' -g '!target/**' -g '!.codegraph/**'` 检查。

用户已在根 `.gitignore` 忽略 `.codegraph`，其内部 `.gitignore` 也保护数据库。保留现有配置，不重复 init，不提交本地索引。

## 验证报告

记录命令、JDK/Maven/OS、通过/失败/跳过数、ZIP 与容器结果；环境阻塞单独说明。不要把旧成功记录或源码审查作为新变更的运行证明。本次真实证据及未执行项见 [项目审查](PROJECT_REVIEW.md)。
