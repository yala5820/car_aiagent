# 车辆知识 RAG 共同依赖、许可证与安全兼容门禁

> 状态：`PHASE_0_TECHNICAL_GATE_PASSED`  
> 适用 Goal：`RAG-G002`（离线主维护）、后续 `RAG-G005`（Android 补充）与 `RAG-G008`（技术收口）  
> 最后事实核查：2026-07-21

## 1. Gate 结论

**项目负责人已于 2026-07-21 批准 ObjectBox 用于本项目的车载 APK/AAB、离线 CLI、预构建知识 Bundle、Vector Search 与目标 ABI 发布范围。准确依赖树与 Java 17 Parser 共存验证已完成，允许进入共享 Schema；批准和单端 Smoke Test 不替代后续跨端 Store、R8、APK/AAB 与目标 ABI 技术验证。**

ObjectBox 运行时由 Java 绑定、Gradle 插件和原生二进制共同组成。其官方 FAQ 说明：Java 库为 Apache-2.0、Gradle 插件为 GPL-3.0、运行时原生库为 ObjectBox Binary Licence。上述许可组合及车机发布范围已经由项目负责人确认；实施 Agent 仍不能自行扩大该批准范围。

本报告据此锁定候选版本并通过依赖 Gate，但不把未执行的漏洞扫描、跨端 Store 或目标 ABI 验证写成已通过。

## 2. 共同边界与已验证事实

- 离线端是 `tools/rag-indexer/` 的独立 Java 17 Gradle Build；Android 根 `settings.gradle.kts` 仍只包含 `:app`。
- `RAG-G001` 尚未声明任何 ObjectBox、PDF、HTML、Markdown、HTTP、JSON、CLI 参数或测试框架第三方依赖，因而不存在“已接入后补审”的情况。
- 已验证离线 CLI 使用 Microsoft JDK 17.0.17；Android 现有构建的 Java 目标也是 17。
- 需要在后续共同 Spike 实测的不是“能否编译”，而是：同一 Meta Model/UID 下，JVM 生成的 `data.mdb` 能否被 Android 使用相同 ObjectBox 版本、Vector Search/HNSW 配置和目标 ABI 原生库稳定只读打开。

### Android 侧补充（RAG-G005）

- Android 基线：AGP 8.9.1、Kotlin 2.0.21、Java 17、`minSdk=33`、`targetSdk=35`；根工程当前仅有 `:app`。
- Android ObjectBox 版本必须固定为 5.4.0，与离线 CLI 的 Plugin/Java Runtime 版本一致；不得由 Android 单独升级或降级。
- 初始目标 ABI 设为 `arm64-v8a`，这是后续车机验证与 APK/AAB 原生库检查的明确候选。本机已使用 SDK 内的 `adb` 启动 Automotive API 35 `x86_64` AVD；项目负责人已授权以此虚拟设备作为 Phase 0 的等效运行环境，且 Android 详细计划明确允许在“目标 ABI 或等效环境”执行 `connectedDebugAndroidTest`。正式发布前仍需补充实际目标 ABI 复测。
- PDFBox、Tabula、jsoup、CommonMark、Jackson、Picocli 仅存在于 `tools/rag-indexer` 的独立 JVM 依赖图，不得进入 Android `app` 依赖或 APK。
- Android 后续只接入 ObjectBox、共享 Entity/Meta Model 和最小 Manifest/Fixture 校验所需依赖；DashScope Query Embedding/Rerank 的 Base URL、区域和请求限制在 Android Phase 2 以现有安全配置渠道接入，凭证不写入版本库、报告或 Trace。

### Android 构建验证（RAG-G006/G007，设备前）

- Android 使用 `sourceSets` 直接消费 `rag-schema/src/main/java`，并由 ObjectBox 5.4.0 在 `app/build/generated/source/kapt/debug/.../MyObjectBox.java` 生成 Android 侧代码；未维护第二份 Entity 或 Meta Model。
- `verifyRagSharedSchema` 对 `rag-schema/objectbox-models/default.json` 与已提交 `default.sha256` 进行构建期校验；Fixture 仅复制到 `app/build/generated/ragTestAssets` 的 androidTest assets。
- `assembleDebug` 与 `assembleDebugAndroidTest` 通过。测试 APK 中的 `assets/rag/knowledge_db/data.mdb` 长度与压缩后长度均为 106,496 字节，证明 `noCompress("mdb")` 生效；主 Debug APK 不含测试 Fixture。
- 主 Debug APK 含 `lib/arm64-v8a/libobjectbox-jni.so`，同时当前 Build 还包含其它默认 ABI；尚未施加 ABI filter，以避免在目标 ABI 实测前错误缩小发布范围。
- 已创建真实 Instrumentation 测试，要求 Android 打开 CLI Fixture 并执行 Dense、postings、Scope/Metadata 查询。`connectedDebugAndroidTest --console=plain` 已在 Automotive API 35 `x86_64` AVD 通过（共 2 个测试）；其中 RAG 测试通过测试 APK assets 复制 CLI Fixture，在目标应用私有目录加载 ObjectBox，并验证 Dense、postings、Scope 正反例及 PDF/静态 HTML/Markdown Locator。Debug APK 已确认含 `lib/arm64-v8a/libobjectbox-jni.so`（2,553,248 字节），测试 APK 的 `data.mdb` 压缩前后同为 106,496 字节。至此 Phase 0 的等效 Android 运行时与打包 Gate 已通过；实际车机 `arm64-v8a` 复测留作正式发布前的额外证据。

## 3. 候选清单（非锁定版本）

| 类别 | 候选 | 已知许可证/事实 | 当前风险与必须验证项 | 状态 |
|---|---|---|---|---|
| 本地 Store、Vector Search | ObjectBox Java / Gradle Plugin 5.4.0 / native runtime / Vector Search | Java binding：Apache-2.0；Gradle Plugin：GPL-3.0；native runtime：ObjectBox Binary Licence | 发布范围已获项目负责人批准；仍需实测 JVM/Android 同库打开、插件/AGP 8.9.1 兼容和 R8 打包。 | TECHNICAL_PENDING |
| PDF 文本与版面 | Apache PDFBox 2.0.37 | Apache-2.0；官方提示处理加密 PDF 涉及出口管制说明。 | 为与 Tabula 1.0.5 的 PDFBox 2.x 依赖共存而锁定 2.x；Tabula 带入的可选 JAI/JBIG2 已在 Gradle 边界排除。 | TECHNICAL_PENDING |
| PDF 文本型表格 | Tabula Java 1.0.5 | MIT | 版本较旧；已确认其请求 PDFBox 2.0.24 且被统一解析为 2.0.37。保留 Bouncy Castle、JTS、Commons CSV、Gson 等传递依赖待许可证清单核验；不能使用来源不明 Fat JAR。 | TECHNICAL_PENDING |
| 严格静态 HTML DOM | jsoup（官方当前发布页显示 1.22.2） | MIT | 只能离线读取本地内容，不使用 `connect()`；配置节点、属性、文本、嵌套深度和文件大小上限；使用已修复已知安全问题的版本。 | PENDING |
| Markdown + GFM Table | commonmark-java 与 `commonmark-ext-gfm-tables`（官方示例 0.28.0） | BSD-2-Clause | 0.x API 不保证稳定；必须锁定同一版本并限制为 CommonMark + GFM Table，禁止隐式启用其他扩展。 | PENDING |
| JSON/严格配置校验 | Jackson core/databind + JSON Schema Validator，或在 Task 1.2 选择等价组合 | 待按最终坐标与传递树核验 | 不能只因 Android 现有 Gson/kotlinx-serialization 即假定 CLI 可复用；需要未知字段拒绝、Schema 版本和诊断能力。 | PENDING |
| CLI 参数 | Picocli，或小型手写参数层 | Picocli 候选为 Apache-2.0，待锁定版本核验 | Task 1.1 的命令面有限；若只需固定四命令与路径参数，优先评估手写层以减少依赖。 | PENDING |
| HTTP 与测试 | OkHttp 4.12.0；MockWebServer 后续按同版本接入 | Apache-2.0，待完整扫描复核 | 当前只接入 HTTP Client；Task 3.3 才引入 MockWebServer，凭证不得进入测试、报告或日志。 | TECHNICAL_PENDING |
| 测试 | JUnit Jupiter 5.12.2 | EPL-2.0，待完整扫描复核 | 用于让 Gradle `test` 真实执行测试；入口 Smoke Test 保留为无第三方断言层。 | TECHNICAL_PENDING |
| 日志绑定 | `slf4j-nop` 1.7.32 | MIT，待完整扫描复核 | Tabula 仅需要 API；使用 NOP binding 避免未配置日志后端时产生误导性标准错误输出。 | TECHNICAL_PENDING |

## 4. 官方事实来源

- [ObjectBox Licensing FAQ](https://objectbox.io/faq/)：明确列出 Java libraries、Gradle plugin 和 native libraries 的不同许可证类别。
- [ObjectBox Java 5.4.0 API](https://objectbox.io/docfiles/java/current/) 与 [官方 JVM/Android Gradle 接入说明](https://docs.objectbox.io/getting-started)：版本和插件接入方式仅作候选技术事实，不等于本项目批准。
- [Apache PDFBox 下载页](https://pdfbox.apache.org/download.cgi) 与 [依赖说明](https://pdfbox.apache.org/2.0/dependencies.html)：Apache-2.0、当前发布线以及可选图像组件/传递依赖风险。
- [Tabula Java 仓库](https://github.com/tabulapdf/tabula-java)：MIT 许可证和当前公开发布节奏。
- [jsoup 仓库](https://github.com/jhy/jsoup)：MIT 许可证与当前发布信息。
- [commonmark-java 仓库](https://github.com/commonmark/commonmark-java)：BSD-2-Clause、GFM Table 扩展及 0.x API 稳定性说明。

## 5. 尚未完成的审查动作

1. 已获得项目负责人对 ObjectBox Binary Licence、GPL-3.0 构建插件、Vector Search 与目标车机发布范围的确认；批准日期为 2026-07-21。
2. 使用已锁定候选执行 `dependencies`/`dependencyInsight`，输出直接与传递依赖、仓库来源、许可证文本和校验和；版本变动必须重新审查。
3. 用批准的版本完成同一 Java 17 进程 Parser 共存 Smoke Test：PDF 文本、Tabula 表格、静态 HTML DOM、CommonMark/GFM Table。
4. 用批准的 ObjectBox 版本完成 `RAG-G003-G008`：共享 Entity/Meta Model、JVM Fixture、Android 目标 ABI 打开、HNSW/Dense/Lexical/Scope/Locator 正反例。
5. 运行组织认可的漏洞扫描。扫描工具、数据库版本、执行日期、发现项和豁免审批必须留档；工具不可用时只可记为“未执行”。

已解析的运行时依赖、传递依赖、排除项与实际 Smoke Test 证据见：[离线运行时依赖解析记录](../../../tools/rag-indexer/dependency-license-report/runtime-dependency-resolution.md)。

## 6. 阻断与恢复条件

以下任一情况保持 `FAILED_GATE`，不得开始 Schema、Parser、索引或 Android RAG 功能实现：

- ObjectBox 的商业发布、目标 ABI、Vector Search 或 Binary Licence 未获得明确批准；
- Tabula 与选定 PDFBox 的准确版本/传递树冲突；
- 任何候选依赖不来自 Maven Central 或项目负责人批准的受信仓库；
- 漏洞或许可证例外没有被负责人接受；
- CLI 与 Android 无法使用同一 ObjectBox Schema/UID/版本和 HNSW 配置。

恢复时先更新本报告的版本表与依赖树证据，再执行最小共存/跨端验证。不得以 Fixture、编译通过或单端运行成功替代上述 Gate。
