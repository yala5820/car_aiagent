# Demo 前向视觉问答人工验收清单

补齐 `assets/vision/demo/images/` 图片并更新配置后，依次验证：默认图片成功、Eval permit 覆盖图片、非法 imageId、缺图、超大图、VLM 非法 JSON、网络失败、60 秒超时、VLM 执行中取消、REQUIRED 未调用 Tool、普通聊天、复合视觉车控请求、IMAGE/VOICE 不支持、CONTROL 三命令、主动场景链、Phoenix 视觉 Trace，以及日志/SQLite 不含 Base64。

当前没有正式图片时，自动化测试和构建可通过；真实视觉 Tool 预期为 `CONFIG_NOT_READY`，所有需要 DashScope 与设备/Phoenix 的项目应标记为 BLOCKED，不得标记 PASS。
