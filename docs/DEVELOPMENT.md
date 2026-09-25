# 开发与验证

先阅读根 [AGENTS.md](../AGENTS.md)。当前目标为 Elasticsearch 9.3.4、9.5.4 / Java 21，单模块 Maven 构建，无 Maven Wrapper。关于原生 ES 方案参见 [版本与定位](ELASTICSEARCH_SUPPORT.md)。

## 环境和构建

推荐 JDK 21、Maven 3.9.x、Python 3（交付检查脚本），Docker 用于容器验收。先确认 Maven JVM：

```sh
java -version
mvn -version
# macOS 多 JDK 环境
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
mvn -version
```

在仓库根执行：

```sh
mvn clean verify
python3 scripts/verify-package.py
mvn -Drevision=9.3.4 clean verify
python3 scripts/verify-package.py --version 9.3.4
```

两个版本应分别从 `clean` 开始，避免复用前一版本编译产物；第二次构建会删除前一版本的 `target` ZIP，需按需另存。缓存齐备时可加 `-o`。Central 下载失败需检查 Maven 仓库/代理配置；shell 代理变量不保证 JVM 自动使用代理，不提交个人 settings.xml 或凭据。`-DskipTests package` 只可排查打包，不是功能验证。

## 测试分层

| 测试 | 主要覆盖 |
| --- | --- |
| `SnapshotFilterTest` | 普通/Graph 空词库切换、稳定快照、多词位置与偏移、reset 复用、并发发布/分析 |
| `LocalSynonymFileTest` | 相对/绝对路径、时间回退、删除恢复、失败重试、空文件、WordNet、expand/lenient |
| `RemoteSynonymFileTest` | 临时 HTTP 服务；304、校验头提交、缺失头、405/501、GET 失败/解析失败恢复、超时、关闭取消请求 |
| `DynamicSynonymFactoryTest` | 自动轮询、按分析链隔离 parser、两类 filter 链式使用、大小写、配置校验、初始化/关闭清理 |
| `scripts/smoke-test.py` | CI 中在对应版本的官方 ES 容器安装 ZIP 后，实际 `_analyze` 验证本地词库与受控 HTTP 词库的更新、失败保留和恢复，并检查 HTTP 索引删除后停止轮询 |

当前无人工改词库或依赖固定外部服务的跳过测试。CodeLibs 的 ClusterRunner 未提供 ES 9.x 版本，原 8.7.1 类路径节点测试不再编译；官方发行版安装验证由 CI 容器 smoke 承担。

测试使用 JUnit 断言，自动创建/清理临时文件和服务。HTTP 服务选择临时端口；沙箱限制监听时，应在允许监听的环境重跑，不禁用测试制造成功记录。新测试用有截止时间的条件轮询代替人工等待。

## ZIP 与安装验收

```sh
python3 scripts/verify-package.py
unzip -l target/releases/elasticsearch-analysis-dynamic-synonym-9.5.4.zip
unzip -p target/releases/elasticsearch-analysis-dynamic-synonym-9.5.4.zip plugin-descriptor.properties
```

脚本检查描述符生效属性、精确版本、必需文件/依赖、重复条目、禁止打包的宿主/测试依赖及 Docker 版本对齐。注释中的历史占位符不影响加载。根 LICENSE 依据原有 POM 的 Apache 2.0 声明补齐，NOTICE 保留 Lucene 归属，各依赖 JAR 保留自身许可。

完整安装验证在隔离 ES 节点进行。`scripts/smoke-test.py` 创建唯一测试索引，验证两种 filter 的空→非空、非法词库保留、清空及恢复，最后删除测试索引。设置 `SYNONYM_HTTP_HOST` 后还会启动受控 HTTP 词库，验证远程更新、失败保留、恢复与删除后的轮询停止。它要求 `SYNONYM_FILE` 是专用可写测试文件，并映射到目标节点的 `config/synonym-smoke.txt`；不要指向生产词库或生产集群。

```sh
ES_VERSION=9.5.4 ES_URL=http://127.0.0.1:19200 SYNONYM_FILE=/absolute/test-config/synonym-smoke.txt SYNONYM_HTTP_HOST=127.0.0.1 python3 scripts/smoke-test.py
```

此脚本要求无认证的本地隔离测试节点；不要为运行它关闭生产认证。Docker CI 使用 `host.docker.internal` 连接临时 HTTP 词库，仅在隔离 runner 绑定服务。单元测试与真正发行版安装验证要分别报告。

## Docker 与 CI

```sh
make build_image
make build_image_from_zip  # 使用已验证的 target/releases/*.zip，需先运行 Maven 构建
# 仅预览发布命令，不实际发布
make -n push_image image_host=example/ image_tag=v9.5.4
```

本地 `make build_image` 的 Docker builder 使用 JDK 21，包含全部 src 并执行所选 `revision` 的 Maven 验证。`make build_image_from_zip` 使用 `Dockerfile.package`，直接把 Maven 验证后的 ZIP 装入相同版本 ES 镜像，避免再次编译。9.3.4 构建传入 `plugin_version=9.3.4`。两种构建都要求 Docker daemon。

`.github/workflows/verify.yml` 覆盖 PR、分支 push、手动触发及可复用调用。矩阵对 9.3.4、9.5.4 分别用 Temurin 21 执行 Maven 与 ZIP 检查，上传独立 JUnit 报告和 ZIP。对应的 `smoke` job 下载同版本 ZIP，在隔离的官方 ES 容器执行安装验收。两个 job 都有超时；新提交会取消同一 PR/分支尚在运行的旧验证。容器只绑定 loopback 端口并使用临时词库。

发布 workflow 在 `v*.*.*` 标签 push 时触发，仅接受清单中的 `v9.3.4`、`v9.5.4`，再调用完整 verify。通过后下载标签对应 ZIP，构建并推送明确版本的 Docker 镜像，随后创建或更新对应 GitHub Release 的 ZIP 附件。镜像命名空间来自 `DOCKER_USERNAME` secret，凭据为 `DOCKER_PASSWORD`；Release 使用该 job 的 `contents: write` 权限。已有 Release 的重跑会替换同名 ZIP。工作流不会从普通分支或手动验证发布。仓库需要允许 Actions 写入 Release，且 Docker 凭据已配置；不能把工作流文件检查当成远端发布成功。

`make push_image` 需要显式 `image_host`，只推送所选标签；不再使用 `docker push -a` 或自动发布 latest。`image_tag` 默认使用 POM 的 `revision`，可通过命令行覆盖。只有用户明确要求才执行发布或推送标签。

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
