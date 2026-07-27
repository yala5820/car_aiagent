# Android RAG Store Instrumentation 测试报告

状态：`HISTORICAL_V4_RESULT`。本报告记录此前 Android 端使用开发 Fixture 与 V4 `TEST_ONLY` 真实资料候选的 Asset 安装、无主 Asset 降级和只读 ObjectBox 读取链路；不代表当前 Parent-Child V2 Bundle 的设备 Gate 已完成，也不代表 `RAG-G905` 已完成。

> 当前 V2 验收状态：`connectedDebugAndroidTest` 已切换到 `model_y_title_v2_candidate` 并成功构建测试 APK，但本机 AVD 未注册为 ADB device，Gradle 返回 `No connected devices!`。下文 V4 结果仅作为历史基线保留。

## 执行环境

| 项目 | 值 |
|---|---|
| 设备 | `Automotive_1408p_landscape` AVD |
| Android 版本 | API 35（Android 15） |
| ABI | `x86_64` 模拟器 |
| 执行命令 | `:app:connectedDebugAndroidTest`，其中 V4 专项类由 Instrumentation Runner 筛选执行 |
| 测试 Asset | 仅 `androidTest` 生成的 Fixture、G404 开发候选和本机 V4 候选；不写入 main APK Asset |

## 结果

| 测试 | 结果 | 验证内容 |
|---|---|---|
| `KnowledgeAssetInstallInstrumentedTest#missingMainAssetFailsOnlyKnowledgeInitialization` | 通过 | main Asset 缺失时，仅知识能力初始化受控失败，非知识 Service 不依赖 RAG READY。 |
| `KnowledgeAssetInstallInstrumentedTest#installsG404DevelopmentCandidateFromTestAssets` | 通过 | 从 `androidTest` Asset 安装开发候选，不把其视为正式发布内容。 |
| `ObjectBoxKnowledgeStoreGatewayInstrumentedTest#readsOfflineFixtureThroughReadOnlyGateway` | 通过 | 使用只读 Gateway 打开离线 Fixture 并完成读取。 |
| `ModelYV4KnowledgeBundleInstrumentedTest#installsAndSearchesLocalV4CandidateWithTrustedModelYScope` | 通过 | 将 78,434,304 字节的 V4 `data.mdb` 从 test APK 流式复制至 App Cache，校验 Manifest/Hash/Store Metadata/Model Y Scope，随后在 Android ObjectBox Runtime 中执行“北京服务中心”的 BM25 查询和真实 Child 向量的 Dense 查询。 |

V4 专项 JUnit XML 结果：1 项测试、0 Failure、0 Error、0 Skipped。运行设备为 `Automotive_1408p_landscape`（API 35，`x86_64`）。

专项通过后，已在同一 AVD 重新执行完整 `:app:connectedDebugAndroidTest --console=plain`：共 7 项 Instrumentation 测试，0 Failure、0 Error。该回归确认 Model Y Profile、V4 测试资产和 Manifest 协议修复没有破坏既有 RAG Store 设备测试。

## 边界与后续门禁

- 本次没有把 V4 `TEST_ONLY` Bundle 放入 Android main Assets；V4 只从 `tools/rag-indexer/trial-output/...` 生成到 `androidTest` Asset，缺少本机候选时专项测试会明确跳过，不能以空 Asset 伪造通过。
- 真实 `APPROVED` Bundle 仍须依次经过 `RAG-G903`、`RAG-G904`、`RAG-G905` 与 `RAG-G906` 的 Metadata、质量阈值、Hash/Schema/Scope/ABI 和安装/升级/回滚门禁。
- 本轮修复了 Android Manifest 对 `parsers.html.embeddedDataExtraction` 的协议遗漏：旧 Bundle 缺省时兼容，显式声明时仅允许 `NONE` 或 `TESLA_SERVICE_CENTERS_V1`，未知策略仍拒绝激活。
- Android Hybrid 的真实 Query Embedding/RRF、Citation、No-Evidence、Faithfulness 仍由 `RAG-G907` 覆盖，本报告不能替代该 Goal。
