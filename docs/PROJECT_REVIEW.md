# 项目审查与修复记录

日期：2026-09-19。初次审查基线 `d185d39`。本次按用户要求核实 ES 原生方案后，修复当前 ES 8.7.1 分支；未升级插件版本，也未宣称兼容 ES 9.x。

## 定位结论

ES 7.3 起支持原生文件搜索分析器重载，8.10 起支持 Synonyms API 管理集合并自动重载，9.x 继续支持。新项目优先使用原生能力；插件保留直接 HTTP(S) 词库接入和自动文件轮询的价值。官方来源及迁移示例见 [ELASTICSEARCH_SUPPORT.md](ELASTICSEARCH_SUPPORT.md)。

## 修复与证据

| 原发现 | 当前处理 | 验证 |
| --- | --- | --- |
| Docker JDK 8 与目标 17 冲突 | builder 改 JDK 17；POM 使用 `--release 17`；Docker 包含完整测试源码 | Maven 编译与版本/ZIP 检查通过；Docker 本地运行未完成 |
| 空词库初始化绕过后续更新，运行中空 map 破坏 filter | 始终创建 wrapper，空 map 透传；下次 reset 可切换任意有效版本 | 两类 filter 的空→非空→空及 ES 节点实际更新通过 |
| 后台更新线程直接改写活跃 TokenStream | volatile 发布不可变 map，reset 固定版本；委托 Lucene 原生算法，移除复制算法和弱引用登记 | 双类型稳定快照测试、并发读写测试、多词位置长度/偏移及 reset 复用通过 |
| HTTP/本地读取错误被转成空规则或 `1=>1` | 首次失败拒绝初始化；刷新失败保留旧版本并重试 | HTTP 503、断连/超时、非法规则、本地删除与恢复测试通过 |
| 版本标记在解析前提交，失败后不重试 | 成功完成读取与解析后才提交；远程使用同一次 GET 的校验头；失败保持 retry | 同版本失败→恢复、HEAD/GET 之间版本变化测试通过 |
| 一个工厂复用多个分析链的 parser | 每个特化链独立 source、Analyzer 和 map，初始化由工厂锁串行化 | 同工厂不同前置 lowercase 链、并发特化测试通过 |
| 工厂/Analyzer/定时任务生命周期不完整 | 初始失败释放并注销；索引移除关闭工厂；终止 HTTP、协调加载、关闭解析 Analyzer；重复关闭安全 | 失败初始化回调、重复关闭、在途 HTTP 取消、实际 ES 索引删除后的 monitor 退出通过 |
| Graph 特化工厂未提供 identity 同义词过滤器 | 两类特化共用实现，统一返回 identity | 两类链式能力契约验证通过 |
| 远程测试跳过、相对路径名不副实 | 改成自动控制 HTTP 服务；ES config 下真实相对路径验证；使用 JUnit 断言 | 当前全部 28 项执行，无跳过 |
| HEAD 不支持的服务无法更新 | HEAD 405/501 回退 GET；保留 304 与校验头优化 | 受控 HTTP 回归通过 |
| HTTP 资源和超时边界 | 显式建连/连接池/响应超时；标准 charset 解析；最多一次额外重试；限制 Retry-After 等待；关闭终止请求 | 超时恢复、连接关闭、UTF-8 charset 测试通过 |
| 旧 HttpClient 4 assembly 坐标、虚构 MainClass | 移除旧 dependencySet 和无效 manifest，固定 assembly 版本 | ZIP 结构/依赖验证通过，旧坐标警告消失 |
| Docker 排除测试，缺 PR 门禁 | 完整 src 进入构建；新增 PR/分支 Maven、ZIP、容器 smoke workflow | YAML 语法及本地 Maven/ZIP 已验证；远端 CI 尚未触发 |
| 旧发布 action / set-output / 未定义镜像变量 / 全标签推送 | 升级 action；从 github.ref_name 取版本；显式 Docker 命名空间；只发布所选标签；发布依赖 verify | YAML 和 make dry-run 已验证；未执行发布 |
| 非法 interval、陈旧注释、周期 info 噪声 | 非空路径及正数 interval 校验，更新实现/文档；成功刷新 debug、失败简短 warning | 配置测试与源码审查通过 |
| 缺根许可及归属材料 | 按原 POM Apache 2.0 声明补 LICENSE，保留 Lucene 归属 NOTICE；加入 ZIP | 产物检查通过；第三方 JAR 自带许可未改写 |

## 实测基线

环境：macOS 26.6.2 / arm64、Oracle JDK 17.0.8、Maven 3.9.5，使用已缓存依赖离线运行。测试在允许本地监听的环境执行。

- `mvn -o clean verify`：成功，28 项测试、0 failures、0 errors、0 skipped，约 21 秒。
- 实际启动 ES 8.7.1 单节点，通过 `_analyze` 验证本地/HTTP 自动热更新、失败保留/恢复、索引删除后的线程清理。该节点由 ClusterRunner 加载类路径插件，不等价于官方发行包的安全策略/安装验收。
- `python3 scripts/verify-package.py`：检查 ZIP 根条目、描述符属性、依赖、版本及 Docker 工具链配置。
- ZIP 根目录 10 个条目：描述符、策略、LICENSE、NOTICE、项目 JAR、analysis-common 8.7.1、httpclient5 5.2.1、httpcore5 5.2、httpcore5-h2 5.2、slf4j-api 1.7.36。不包含 ES/Lucene/Log4j/JUnit JAR。
- 工作流 YAML 语法解析和 `make -n build_image push_image image_host=example/` 通过；未实际推送镜像。
- 本次修改路径的文档链接、脚本语法和 diff 空白检查通过。提交时按用户要求纳入根 `.gitignore` 的 `.codegraph` 忽略规则，并统一为 LF 换行。
- 源码变更后执行 CodeGraph sync；索引用于导航，不作为运行证明。

本地构建日志为 `/tmp/dynamic-synonym-fixed-verify.log`，测试报告位于 `target/surefire-reports/`。后续 clean/构建会覆盖报告，以上是本次结果快照。

## 仍需环境验收或后续适配

- 本机 Docker CLI 存在，但 daemon 未启动，故未实跑 Docker 构建和容器 smoke。已提供 `verify.yml` 及 `scripts/smoke-test.py`，不能称远端 CI 已通过。
- 尝试下载官方 ES 8.7.1 macOS 发行包，在 180 秒超时后只收到约 21 MB/385 MB；未完成独立发行包 ZIP 安装或其 SecurityManager、模块 classloader、宿主 Log4j 兼容验证。POM 的 Log4j 仍为 provided，由实际宿主提供。
- 未进行 Windows/Linux 原生运行、ES 其他 8.x 或 9.x 适配、集群级长时间负载/内存测量、远端 CI 或发布。
- 快照一致性是单次分析周期内保证；节点间轮询异步、不自动清理 request cache、不重写旧索引。大词库规模上限与整个下载的绝对总时限尚未新增产品配置。
- 本地同尺寸且原地写入后恢复相同 mtime 的修改不保证检测；词库发布应更新时间或原子替换。已有分析链状态保留至工厂随索引移除关闭。

## 历史审查范围

初次审查覆盖生产 Java 类、测试、POM、描述符/策略/assembly、Docker、Makefile、workflow 和 README；大型 `ti_synonym.txt` 仅核对规模和引用，未逐条校验、改写。修复前自动测试为 3 项通过、1 项远程测试跳过，CodeGraph 初始索引 15 文件/369 节点/631 边。
