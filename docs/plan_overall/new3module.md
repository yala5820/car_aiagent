我的默认建议顺序是：

```text
3. Eval 基础框架
    ↓
2. 前向视野问答
    ↓
1. RAG 车辆手册
```

但这里的“Eval 优先”是指先建立一个轻量 Demo 骨架，不是先开发一套复杂评测平台。

## 1. 第一阶段：Eval Demo 骨架

Eval 应该最先引入，因为后面增加 VLM、RAG、Prompt 和模型版本时，都需要回答：

- 新能力到底有没有变好；
- 是否破坏现有车控、ToolGroup、Memory 和 Safety；
- 模型最终答复是否与工具结果一致；
- 延迟、Token、工具轮数增加了多少。

当前项目已经有 RuntimeResult、ToolHistory、VehicleStateMachine 和 Trace，具备较好的评测数据基础。

第一版只需要四个概念：

- `EvalCase`：输入、初始车辆状态、预期行为；
- `EvalRunner`：执行一次 Agent 请求；
- `Grader`：检查 Intent、ToolGroup、工具、参数、状态变化和答复；
- `EvalReport`：输出 JSON/Markdown 汇总。

建议先建立20～30个现有能力用例，例如：

- 普通聊天不调用工具；
- 空调指令选择正确 ToolGroup；
- 高风险动作要求确认；
- 行驶中拒绝解锁；
- 取消和超时不会产生迟到成功；
- 会话和用户记忆正确隔离。

第一版优先采用代码评分，不急着引入 LLM-as-Judge。在线模型用例可以每个运行3次，记录成功率、延迟和轨迹。

Agent Eval 应同时检查最终结果和完整执行轨迹，并在能力开发前定义成功条件，这是目前比较推荐的 Eval-driven development 方式。[Anthropic Agent Eval 方法](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)

## 2. 第二阶段：前向视野问答

严格来说，这项能力并不是从零开始。

当前 [VlManager.java](/D:/code/android/AndroidStudioProjects/AIAgent/app/src/main/java/com/hirain/aiagent/tools/vision/vl/VlManager.java:115) 已经具备：

- `qwen-vl-max` 模型调用；
- `front_camera_interaction` 工具；
- 图片 Base64 编码；
- 摄像头图片缓存；
- 没有实时图片时读取 `assets/documents/audi.jpg`。

因此，这一阶段主要是把“单张默认图片”升级成“可重复评测的模拟图片数据集”，架构风险和工作量都小于 RAG，很适合用来验证刚建立的 Eval 框架。

建议准备：

```text
assets/eval/vision/
├── images/
│   ├── road_sign_01.jpg
│   ├── pedestrian_01.jpg
│   ├── vehicle_01.jpg
│   └── ambiguous_01.jpg
└── cases.json
```

每个 Case 明确指定：

- `imageId`；
- 用户问题；
- 必须识别的对象或属性；
- 允许的同义表达；
- 禁止编造的内容；
- 图片无法判断时是否应该明确说明不确定。

建议首批15～30张图片，每张2～3个问题，覆盖：

- 前车、行人、道路和交通标志；
- 天气、光照和路面情况；
- 图片中不存在目标的问题；
- 模糊、遮挡或无法确认的情况。

主要指标：

- 目标识别正确率；
- 属性回答正确率；
- 无依据内容编造率；
- 不确定场景正确拒答率；
- ToolGroup和视觉工具选择正确率；
- VLM调用延迟。

## 3. 第三阶段：RAG 车辆手册

RAG 放在最后不是因为它价值低，而是它对架构的影响最大。

它不仅是“增加一个依赖”，还涉及：

```text
文档解析
  → 清洗与分段
  → 元数据
  → Embedding
  → 向量存储
  → 查询改写
  → 召回
  → 排序
  → Context 注入
  → 来源引用
  → 无答案处理
```

LangChain4j 已提供 Document、EmbeddingModel、EmbeddingStore、ContentRetriever、EmbeddingStoreIngestor 和 RetrievalAugmentor 等基础能力，可以复用这些通用原语。[LangChain4j RAG 文档](https://docs.langchain4j.dev/tutorials/rag/)

但不建议因此改成 LangChain4j `AiServices` 主导编排。比较适合当前项目的设计是：

```text
ManualQaIntent
  → ManualKnowledgeRetriever
  → VehicleManualContextProvider
  → ContextContribution
  → ContextMessageAssembler
  → qwen-turbo
```

即：

- LangChain4j 负责文档、Embedding、向量检索等基础能力；
- 自研 Context 继续拥有模型输入装配权；
- 检索内容标记为 `UNTRUSTED_DATA`；
- 每个片段保留车型、手册版本、章节、页码等来源元数据；
- 只在车辆手册意图下执行检索，不要每个请求都注入。

RAG 开发前，先准备一批人工答案明确的问题，例如：

- 胎压报警灯亮起应该怎么办；
- 雨刮片如何更换；
- 如何开启儿童锁；
- 推荐使用什么型号的机油；
- 手册中没有说明某项功能时应该如何回答。

评测应拆成两层：

- 检索层：Hit@K、Recall@K、正确章节召回率；
- 回答层：事实正确率、引用正确率、Groundedness、无答案拒答率。

这样才能区分“没有检索到正确内容”和“检索正确但模型回答错误”。

## 推荐的实际推进方式

```text
Eval v0：先评测现有 TEXT / Tool / Safety
    ↓
添加视觉失败用例
    ↓
实现图片数据集与前向视野问答
    ↓
视觉用例进入回归集
    ↓
添加 RAG 检索与回答失败用例
    ↓
实现车辆手册 RAG
    ↓
形成 TEXT + Tool + Vision + RAG 综合 Eval
```

如果你的近期目标是快速做出可演示成果，推荐保持：

> **Eval 骨架 → 前向视野问答 → RAG。**

如果车辆手册问答是当前最核心的业务交付，也可以调整为：

> **Eval 骨架 → RAG → 前向视野问答。**

但无论如何，我不建议把 Eval 放到最后。Eval 应该先建立最小骨架，然后跟随每项能力同步增长。