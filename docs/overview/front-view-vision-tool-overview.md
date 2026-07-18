# TEXT Demo 前向视觉问答能力

当前前向视觉问答只接受 TEXT 请求。Runtime 使用规则型 VisionIntentPolicy 将请求区分为 REQUIRED、OPTIONAL、NONE；明确“前方是什么”类请求仅向模型暴露 `front_camera_interaction`，并以 ToolChoice.REQUIRED 要求先取得视觉证据。

Tool 从 `assets/vision/demo/vision-demo-config.json` 的白名单读取图片，校验路径、大小、MIME 和文件签名后交给 qwen-vl-max。Tool 只把结构化摘要、观察项和不确定性返回给 TEXT Loop，不保存图片或 Base64。空配置是合法状态，真实请求会返回明确失败，不会静默使用旧图片。

视觉请求从 AIDL 收到时刻开始使用 60 秒绝对期限，普通 TEXT 保持 30 秒；VLM HTTP Call 复用现有取消与 deadline 上下文。Demo 图片覆盖只允许 Debug 中持有有效 Eval permit 的请求使用。

限制：未接入真实摄像头、按需取帧、画面新鲜度或道路安全判断；当前图片数据未补齐，因此真实 VLM 端到端验收仍待人工执行。
