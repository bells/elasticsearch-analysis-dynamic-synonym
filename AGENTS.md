# 项目协作指南

本文件适用于整个仓库。面向用户的沟通默认使用中文；公开 README 保持英文，Java 标识符沿用现有风格。

## 开始任务前

1. 阅读本文件、`README.md` 和与任务有关的文档：
   - `docs/ELASTICSEARCH_SUPPORT.md`：原生 ES 能力、插件价值和版本边界。
   - `docs/ARCHITECTURE.md`：组件职责、加载链路和运行时边界。
   - `docs/DEVELOPMENT.md`：环境、构建、测试和发布检查。
   - `docs/PROJECT_REVIEW.md`：有日期的审查快照、已知问题和验证缺口。
2. 检查 `git status --short`、相关源码及构建配置，保护已有修改。
3. 以当前代码和 `pom.xml` 为事实依据；历史文档和注释可能过期。
4. 修改前简要说明设计考量。涉及热更新、兼容版本或失败语义时，先明确预期行为和验证方法。

## CodeGraph 导航

仓库已初始化 CodeGraph；优先运行 `codegraph status` 检查索引，再用 `query`、`node` 和 `impact` 定位符号及影响范围。命令示例见开发文档。索引过期或修改源码后运行 `codegraph sync`；无需重复 `init`，也不要自行 `uninit` 或修改全局 agent/MCP 配置。

CodeGraph 用于辅助导航，源码、构建配置和实际测试仍是最终依据。当前索引没有覆盖全部文档、Dockerfile、Makefile、词库和安全策略，必须配合 `rg` 和直接阅读。Java 插件注册、配置字符串、继承与动态调用可能让 `affected` 漏报：即使返回无受影响测试，修改工厂/词库/filter 时仍执行相关 Maven 测试。

保留根 `.gitignore` 与 `.codegraph/.gitignore` 中现有的索引忽略设置；数据库、WAL、日志等本地索引文件不入库，不手改数据库。索引统计是快照，不把节点数或命中数当成测试覆盖率。

## 项目定位与目录

这是单模块 Maven Java 项目，提供 Elasticsearch 的 `dynamic_synonym` 和 `dynamic_synonym_graph` token filter。没有 Spring Boot 服务、前端、Rust 或 Tauri 模块。

| 路径 | 职责 |
| --- | --- |
| `pom.xml` | 版本、依赖、Java 编译目标、测试及打包配置 |
| `src/main/java/com/bellszhu/elasticsearch/plugin/DynamicSynonymPlugin.java` | 插件注册、工厂跟踪和关闭 |
| `src/main/java/com/bellszhu/elasticsearch/plugin/synonym/analysis/` | 分析链工厂、词库读取、轮询和 Lucene token filter |
| `src/main/resources/` | 插件描述符与安全策略 |
| `src/main/assemblies/plugin.xml` | 可安装 ZIP 的布局和依赖选择 |
| `src/test/java/com/bellszhu/elasticsearch/plugin/synonym/analysis/` | 词库、快照和工厂回归测试 |
| `scripts/` | ZIP 检查与官方发行包隔离节点 smoke |
| `src/test/resources/` | 测试词库与日志配置 |
| `ti_synonym.txt` | 大型词库文件，未被当前测试或打包显式引用 |
| `Dockerfile`、`Makefile`、`.github/workflows/` | 容器构建及标签触发发布 |

## 实现约束

- 当前源码的明确目标为 ES `9.3.4`、`9.5.4`，默认 `9.5.4`，Java 编译目标为 `21`。经典插件 ZIP 要求精确匹配宿主版本；旧版使用历史 release 包。
- 版本升级同时检查 POM 中的 Elasticsearch 依赖、描述符模板、Docker 基础镜像、CI 矩阵及 README；不要直接编辑 `target/` 生成的描述符。
- 保持插件入口、分析链工厂、词库 I/O 和 token 处理的职责分离。优先复用 Elasticsearch/Lucene parser 与现有 HttpClient 5，不自行重写通用解析器或 HTTP 客户端。
- 同义词解析必须使用当前分析链的 tokenizer、char filters 和前置 token filters。不能绕过 chain-aware 工厂直接调用外层 `create()`。
- 两类过滤器的行为不同，普通过滤器修复不能自动代表 graph 过滤器已正确；涉及共享工厂的变化应检查两者。
- 热更新必须先完整构建 map，再通过 volatile 发布；每个 TokenStream 只在 reset 时取快照。不要恢复后台线程改写活跃 filter 或登记 WeakHashMap 的旧实现。
- 修改轮询或词库失败处理时，要覆盖首次加载、成功更新、解析失败、空词库、删除/恢复、远程错误及重试语义。首次失败应阻止初始化；刷新失败保留旧 map 并重试。空内容是有效版本，不得用空规则或占位规则掩盖读取错误。
- 明确 Analyzer、Reader、HTTP response/client、定时任务及 executor 的所有权和释放时机。新增 HTTP 请求需要合理超时、状态码处理和资源释放。
- `updateable=true` 表示仅允许搜索时使用，不是定时刷新开关。配置键拼写属于外部契约，不随意重命名。
- 两个 wrapper 委托 Lucene 原生过滤器，保留现有 Apache 授权头与归属。不要复制算法重新维护；修改 token 处理要验证词项、位置增量、位置长度、偏移量及 reset 后复用。
- 避免输出完整远程词库、带凭据的 URL 或密钥；不要为了让测试通过而扩大 `entitlement-policy.yaml` 权限。

## 修改和验证

- 首选 JDK 21 和 Maven 3.9.x。无 Maven Wrapper，先执行 `java -version`、`mvn -version` 核实 Maven 实际使用的 JVM。
- Java 或依赖修改通常执行 `mvn test`；打包、版本和交付变更执行 `mvn clean verify` 并检查 ZIP 内容。具体步骤见开发文档。
- JUnit 远程测试会监听临时本地 HTTP 端口；官方 ES 节点验证由 `scripts/smoke-test.py` 和 CI 的对应版本容器承担，无人工测试跳过项。必须分别报告通过、失败和跳过数量。
- `-DskipTests` 只可作为编译/打包排查，不能作为功能验证通过的依据。沙箱端口限制或依赖下载失败应记录为环境阻塞，不要改业务代码掩盖。
- 新增回归测试应自动准备和清理临时资源；HTTP 测试使用可控的本地服务，避免依赖固定外部地址、人工改文件或固定长时间睡眠。
- 纯文档变更检查链接、配置默认值、命令和 `git diff --check`。不为文档增加无意义测试。
- 不做无关格式化、批量词库改写或生成物入库；不要使用 `git add .`。未经明确要求不提交、推送、打标签或发布镜像。
- `make push_image` 与 `make build_image_and_push` 会发布明确指定的镜像标签；不能把它们当成本地验证命令。PR/分支 CI 验证 Maven、ZIP 和容器 smoke，标签发布依赖该验证。

## 文档维护

行为或配置变化同步 README；结构变化同步架构文档；验证方式变化同步开发文档。审查项修复后补充日期和真实证据。保持一份根 `AGENTS.md`，只有子目录存在独立规则时才新增嵌套指南。

本仓库目前没有 OpenSpec 或其他 SDD 工具配置。复杂功能可以先写简短设计与验收条件，但不要宣称已有规范工作流，也不要仅为补文档引入框架或多套重复的 AI 指令文件。
