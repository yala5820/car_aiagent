# MainActivity.java 参考内容（已删除）

> 来源：`app/src/main/java/com/hirain/aiagent/MainActivity.java`
> 创建：2026-06-22 | 用途：AgentLoop/Memory/Tool 系统重新设计时的配置和架构参考

---

## 系统提示词

```text
角色定义：
    你是一位专业、友好且高度智能的车载AI助手，专注于提供安全、高效、愉悦的驾驶体验。
    你集成多种人工智能技术，通过不断学习迭代升级功能，在软硬件配合下实现自然流畅的人车智能交互。
    你的核心使命是在保障驾驶安全的前提下，为用户提供全方位的智能座舱服务。
核心原则
    认知友好：从用户认知角度出发，使用简化易懂高效的提示，尽量避免或减少专业术语。
    上下文感知：持续跟踪对话历史，结合当前驾驶状态、地理位置、时间等上下文提供个性化服务。
    主动智能：能够预测用户需求，在适当时机提供主动建议，但不过度打扰。
功能规范
    通用对话能力
        自然聊天：保持友好、专业且符合驾驶场景的对话风格，避免过度拟人化。
        娱乐互动：可根据请求讲笑话/故事，但需控制时长，单次不超过1分钟。
        百科问答：提供准确简洁的信息，复杂问题提供摘要并询问是否需要详情。
        天气查询：使用对应工具查询天气，回答用户关于天气的对话。
        推荐能力
            音乐/影视推荐：结合对话上下文智能推荐。
            旅游景点：结合对话上下文，提供个性化推荐。
            游玩建议：结合对话上下文，提供个性化推荐。
    智能座舱专属功能
        前向窗景互动：
            任务：结合前向窗景识别工具的能力，在用户提及时提供相关信息。
            必须遵守强实时性：用户有前向窗景识别意图时，必须重新调用工具获取并识别前向视野。
                           窗景互动不能依赖对话上下文，必须重新调用工具识别实时前向视野。
        精准控车
            支持自然语言理解的车辆控制，例如：把空调温度调节为22℃ --> 设置空调温度为22℃
            复杂指令拆解：例如：打开车窗通风并播放轻松音乐 --> 分步执行
        模糊控车
            识别隐含需求：例如："有点冷" --> 自动调高空调温度。
交互规范
    话术要求
        保持简洁，单次语音输出不超过30秒。
        模糊控车需要二次确认。
能力边界声明
    关于订单预定等功能将在后续的版本退出，当前版本仅能提供语音或文本建议。
    我能够帮助您控制车辆功能、提供天气信息、娱乐服务和旅途建议，但无法代替您进行驾驶操作。请始终将注意力集中在道路上，安全驾驶。
```

---

## AI 模型配置

| 参数 | 值 |
|------|-----|
| Provider | Alibaba DashScope (OpenAI 兼容) |
| Base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Model | `qwen-turbo` |
| API Key | `sk-11129fb7941f49dbb083039a93a160bc` |
| HTTP Client | `langchain4j.http_client_ok.OkHttpClient` |
| Connect Timeout | 30 秒 |
| Read Timeout | 120 秒 |
| Parallel Tool Calls | true |

---

## 天气服务配置

| 参数 | 值 |
|------|-----|
| Provider | 未知（通过 WeatherUtils 封装） |
| API Key | `c9af807ed95f93b56855a928417586f9` |
| 工具注册 | `ToolSpecifications.toolSpecificationsFrom(WeatherUtils.class)` |

---

## 工具管理器注册清单

9 组工具管理器，统一通过 `ToolSpecifications.toolSpecificationsFrom()` 注册：

| 管理器 | 注册代码 |
|--------|----------|
| WeatherUtils | `ToolSpecifications.toolSpecificationsFrom(WeatherUtils.class)` |
| VehicleDoorManager | `ToolSpecifications.toolSpecificationsFrom(VehicleDoorManager.class)` |
| VehicleWindowManager | `ToolSpecifications.toolSpecificationsFrom(VehicleWindowManager.class)` |
| VehicleSeatManager | `ToolSpecifications.toolSpecificationsFrom(VehicleSeatManager.class)` |
| VehicleAcManager | `ToolSpecifications.toolSpecificationsFrom(VehicleAcManager.class)` |
| VehicleFragManager | `ToolSpecifications.toolSpecificationsFrom(VehicleFragManager.class)` |
| VehicleSpeedManager | `ToolSpecifications.toolSpecificationsFrom(VehicleSpeedManager.class)` |
| VehicleDMSManager | `ToolSpecifications.toolSpecificationsFrom(VehicleDMSManager.class)` |
| VlManager | `ToolSpecifications.toolSpecificationsFrom(VlManager.class)` |

各工具列表通过 Stream flatMap 合并为 `mergedTools`：

```java
private final List<ToolSpecification> mergedTools = Stream
    .of(wheatherTools, doorTools, windowTools, seatTools, acTools, fragTools, vlTools, speedTools, dmsTools)
    .flatMap(List::stream).collect(Collectors.toList());
```

---

## 车辆状态采集模式

每次 AI 请求前，收集所有车辆状态作为 JSON，以 UserMessage 注入对话：

```java
private String getVehicleStatus() {
    JSONObject json = new JSONObject();
    json.put("车门", new JSONObject(doorManager.getDoorStatus()));
    json.put("车窗", new JSONObject(windowManager.getWindowStatus()));
    json.put("座椅、方向盘", new JSONObject(seatManager.getSeatStatus()));
    json.put("空调", new JSONObject(acManager.getAcStatus()));
    json.put("香氛", new JSONObject(fragManager.getFragStatus()));
    json.put("车速", new JSONObject(speedManager.getSpeedStatus()));
    json.put("DMS", new JSONObject(dmsManager.getDmsStatus()));
    return json.toString();
}
```

注入方式：

```java
tmp.add(UserMessage.userMessage("车辆状态", getVehicleStatus()));
tmp.addAll(chatMemory.messages());
```

---

## 工具调用循环（AgentLoop 参考）

递归式 Tool Calling 循环：

```java
private void processAiResponse(ChatResponse aiResponse) {
    AiMessage aiMessage = aiResponse.aiMessage();
    chatMemory.add(aiMessage);

    if (aiMessage.hasToolExecutionRequests()) {
        for (ToolExecutionRequest toolrequest : aiMessage.toolExecutionRequests()) {
            String result = handleTools(toolrequest);
            ToolExecutionResultMessage resultMsg = ToolExecutionResultMessage.from(toolrequest, result);
            chatMemory.add(resultMsg);
        }
        // 递归调用
        ChatRequest request = ChatRequest.builder()
            .messages(chatMemory.messages())
            .toolSpecifications(mergedTools)
            .build();
        ChatResponse response = model.chat(request);
        processAiResponse(response);
    } else {
        // 最终输出
        String text = aiResponse.aiMessage().text();
    }
}
```

---

## 工具路由（handleTools）

```java
private String handleTools(ToolExecutionRequest request) {
    if (doorManager.hasTool(request.name()))      return doorManager.handleToolRequest(request);
    if (windowManager.hasTool(request.name()))    return windowManager.handleToolRequest(request);
    if (weatherutils.hasTool(request.name()))     return weatherutils.handleToolRequest(request);
    if (seatManager.hasTool(request.name()))      return seatManager.handleToolRequest(request);
    if (acManager.hasTool(request.name()))        return acManager.handleToolRequest(request);
    if (fragManager.hasTool(request.name()))      return fragManager.handleToolRequest(request);
    if (vl.hasTool(request.name()))               return vl.handleToolRequest(request);
    if (speedManager.hasTool(request.name()))     return speedManager.handleToolRequest(request);
    if (dmsManager.hasTool(request.name()))       return dmsManager.handleToolRequest(request);
    return "无效的工具调用。";
}
```

---

## 聊天记忆配置

| 参数 | 值 |
|------|-----|
| 实现 | `MessageWindowChatMemory` |
| 持久化 | `PersistentChatMemorySqlite(getApplicationContext(), "ActivityMemory")` |
| Max Messages | 50 |
| 初始化 | `chatMemory.add(SystemMessage.systemMessage(systemPrompt))` |
| 清空重建 | `chatMemory.clear()` → 重新添加 systemPrompt |
