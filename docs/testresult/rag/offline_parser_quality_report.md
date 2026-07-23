# 离线 Parser 质量报告

状态：`TEST_ONLY_TRIAL_COMPLETED`。

本次受控试运行的 Scope 为 `MODEL_Y / 2026 / CN / 2026_REFRESH / RWD`，Scope ID 为 `model-y-2026-cn-2026-refresh-rwd`。用户已授权将资料发送至云端 Embedding；报告不记录正文、绝对路径或 API Key。

| 输入 | SHA-256 | 解析结论 | 后续处理 |
|---|---|---|---|
| Owner's Manual PDF | `9845df2089fc290aaf854696b5a8776af40cfe1dbdd12b50d6c929c48736af98` | 用户批准调整后，PDFBox 以无密码、`canExtractContent=true` 的只读边界成功解析；9,900 个 Block、9,900 个 Locator、0 个表格、37 条解析诊断（35 条重复页眉/页脚、1 条忽略主动 URI、1 条表格提取失败），另有 561 个 WARNING 正文块定位 | 已纳入 PDF+HTML `TEST_ONLY` Bundle；不尝试密码、不解密绕过、不修改原文件。37 条诊断须按 G903 审核清单处理；561 个 WARNING 块是候选知识内容，不等同于 561 个解析失败。 |
| 特斯拉中国车辆质量保证静态 HTML | `a7f2ec5185abc636cefc10419be7a154f753778b76749ab0411bab6bdf4bb178` | 通过；0 Parser Warning、48 个有效 Locator | 纳入 HTML-only `TEST_ONLY` Pilot。 |

试验 Bundle `model-y-2026-refresh-html-pilot` 仅包含上述 HTML：5 个 Parent、22 个 Child、1623 个词项，22 次 Embedding 全成功，`data.mdb` SHA-256 为 `675e938e7de530e9067efbeb135f1271447016ce649f2db8ccbb37ac56e3167f`。`verify` 已输出 `VERIFY_SUCCESS`，但 `publishable=false`，不得用于正式发布或宣称完整车型知识库。
