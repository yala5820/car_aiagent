# Android Parent Evidence V2 验收记录

## 当前状态

Android 侧 Parent Evidence、批量 Parent 读取、RRF 后候选去重、完整 Parent 预算和 `retrievalConfidence=UNASSESSED` 已完成代码实现。受影响类已通过直接 JDK 编译，Parent 聚合、预算、去重和 Golden 定向测试通过。

## 尚未完成的门禁

- `connectedDebugAndroidTest`：已切换到 `model-y-2026-refresh-title-v2` TEST_ONLY 候选并完成 APK/测试 APK 构建，但本机 `Automotive_1408p_landscape` AVD 未注册为 ADB device，Gradle 明确返回 `No connected devices!`，因此设备验收仍未通过。
- 人工 Eval Gate、DEV/TEST 冻结和置信度校准尚未完成。
- 主 APK：没有复制 TEST_ONLY Bundle，保持未交付状态。

## 已完成的本机自动验证

- `:app:testDebugUnitTest`：`452 tests completed, 0 failed`。
- `:app:assembleDebug`：通过。
- `:app:lintDebug`：通过；无 error，保留既有 warning。
- `prepareRagTestAssets`：已复制 `rag/model_y_title_v2_candidate`，Manifest format V2、Embedding template V2、BM25 analyzer V2。
- 离线 CLI `test installDist`：`160 tests completed, 0 failed`，安装分发通过。

## 已确认不变量

- Rerank 只接收 Child；
- Parent 只在 Rerank/RRF 完成后恢复；
- Evidence 返回完整 Parent，不截断；
- 同一 Parent 最终只出现一次；
- 内部 Parent/Child ID 和原始分数不进入 ToolResult JSON；
- 未完成阈值校准前不伪造 HIGH/MEDIUM/LOW。
