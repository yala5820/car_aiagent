# Prompt System Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复当前 AIAgent Prompt 系统评估报告中的 P0/P1/P2 问题，补齐 `PromptSelector` 闭环，建立更规范的模板清单、版本管理、动态组装和单元测试体系。

**Architecture:** 保留当前 `assets/prompts/ + PromptManager + LangChain4j PromptTemplate` 技术栈，不引入新模板引擎。新增轻量 `PromptSource`、`PromptManifest`、`PromptAssembler`，让模板加载、变量校验、动态选择、动态组装分别有清晰边界。先保证主 Agent 行为兼容，再逐步外部化记忆 prompt、上下文 prompt 和可见输出策略。

**Tech Stack:** Android Service, Java/Kotlin, LangChain4j `PromptTemplate`, Android assets, org.json, JUnit4, Gradle unit test.

---

## 0. 执行原则和阶段边界

本计划只描述实施步骤，不在计划阶段修改业务代码。实施时遵循以下约束：

- 不引入 Freemarker、Handlebars 等新模板引擎。
- 不改动 DashScope 模型调用方式。
- 不改动工具注册、车辆状态机和 AIDL 协议。
- 不删除历史文件，除非用户单独确认；`LocalPrompt.kt` 第一阶段只标注废弃。
- 不自动提交 git commit；每阶段完成后汇报可提交文件清单，由用户决定是否提交。
- 所有新增代码注释使用中文，重点解释设计原因。

## 1. 当前问题到阶段映射

| 评估问题 | 阶段 | 处理方式 |
|---|---|---|
| `PromptSelector` 未接入主流程 | Phase 2 | `AgentConfig` 增加 selector，`AgentLoopOrchestrator` 基于上下文选择模板 |
| 长期记忆双路径注入 | Phase 3 | 移除 chat persona 中 `MemoryPreProcessor`，保留 SystemPrompt 注入路径 |
| 记忆压缩/提取 prompt 硬编码 | Phase 3 | 新增 `memory/compress.txt`、`memory/extract.txt`，改造 Memory 类 |
| assets 热更新表述不准确 | Phase 5 | 更新 overview/act_summary 或新增 docs 说明 APK 内置模板边界 |
| 缺少模板变量校验和测试 | Phase 1 + Phase 4 | 加 `PromptSource` 测试缝、`PromptManifest`、变量校验 |
| VL 警告直接进入最终回复 | Phase 3 | 清理用户可见警告，保留系统提示词中的“每次重新取图”规则 |
| `LocalPrompt.kt` 旧 prompt 残留 | Phase 3 | 标记 `@Deprecated`，中文说明不再接入主流程 |
| 缺少 prompt 版本和清单 | Phase 4 | 新增 `assets/prompts/index.json` 和 manifest 加载 |
| 场景识别模型文档不一致 | Phase 5 | 文档明确区分场景识别 VLM 和场景控车 LLM |
| UserMessage 层级混乱 | Phase 3 + Phase 4 | 上下文模板归类，PromptAssembler 统一组装 system/context/user 边界 |

## 2. 目标文件结构

### 2.1 新增文件

- `app/src/main/java/com/hirain/aiagent/prompt/PromptSource.java`  
  模板原文加载接口，隔离 Android assets，方便 JVM 单测。

- `app/src/main/java/com/hirain/aiagent/prompt/AssetPromptSource.java`  
  Android assets 实现，从 `assets/prompts/` 读取 `.txt` 和 `index.json`。

- `app/src/main/java/com/hirain/aiagent/prompt/PromptTemplateSpec.java`  
  单个模板元信息：名称、版本、类型、变量列表、说明、适用 persona。

- `app/src/main/java/com/hirain/aiagent/prompt/PromptManifest.java`  
  读取 `prompts/index.json`，提供模板存在性、变量要求、版本查询。

- `app/src/main/java/com/hirain/aiagent/prompt/PromptAssembly.java`  
  描述一次组装结果：最终文本、模板名、版本、片段列表。

- `app/src/main/java/com/hirain/aiagent/prompt/PromptAssembler.java`  
  负责根据 `AgentConfig`、`AgentLoopContext`、用户 ID 和 selector 组装 system prompt。

- `app/src/main/assets/prompts/index.json`  
  Prompt 清单，记录所有模板元数据和变量 schema。

- `app/src/main/assets/prompts/memory/compress.txt`  
  记忆压缩模板，替代 `MemoryCompressor.buildCompressionPrompt()`。

- `app/src/main/assets/prompts/memory/extract.txt`  
  记忆提取模板，替代 `MemoryExtractor.buildExtractPrompt()`。

- `app/src/main/assets/prompts/context/current_time.txt`  
  当前时间上下文模板，替代 `TimeContextPreProcessor` 中的硬编码中文标签。

- `app/src/test/java/com/hirain/aiagent/prompt/MapPromptSource.java`  
  单测专用内存模板源。

- `app/src/test/java/com/hirain/aiagent/prompt/PromptManagerTest.java`
- `app/src/test/java/com/hirain/aiagent/prompt/PromptManifestTest.java`
- `app/src/test/java/com/hirain/aiagent/prompt/PromptSelectorTest.java`
- `app/src/test/java/com/hirain/aiagent/prompt/PromptAssemblerTest.java`
- `app/src/test/java/com/hirain/aiagent/core/AgentConfigPromptTest.java`
- `app/src/test/java/com/hirain/aiagent/memory/MemoryPromptExternalizationTest.java`

### 2.2 修改文件

- `app/src/main/java/com/hirain/aiagent/prompt/PromptManager.java`  
  改为依赖 `PromptSource`，增加 manifest 可选校验。

- `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java`  
  增加 memory/context 模板常量。

- `app/src/main/java/com/hirain/aiagent/prompt/PromptSelector.java`  
  让 selector 能稳定处理 `scene` 对象、`scene_type`、persona 信息和 null context。

- `app/src/main/java/com/hirain/aiagent/core/AgentConfig.java`  
  增加 `PromptSelector systemPromptSelector`，保留 `systemPromptTemplateName` 兼容现有配置。

- `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`  
  改为通过 `PromptAssembler` 组装 SystemMessage，并把 `AgentLoopContext` 传入 system prompt 注入路径。

- `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`  
  配置 chat/scene/vision persona 的 selector，移除重复长期记忆注入。

- `app/src/main/java/com/hirain/aiagent/core/preprocessor/TimeContextPreProcessor.java`  
  通过模板渲染当前时间。

- `app/src/main/java/com/hirain/aiagent/core/preprocessor/MemoryPreProcessor.java`  
  如果保留，则改成只处理短期检索结果；本计划建议从 chat persona 中移除，不删除类。

- `app/src/main/java/com/hirain/aiagent/core/postprocessor/VlWarningPostProcessor.java`  
  不再把内部警告拼到用户可见回复；保留类时改为 no-op 或改名处理需另行确认。

- `app/src/main/java/com/hirain/aiagent/tools/vision/vl/VlManager.java`  
  `frontCameraInteractionPositive()` 返回干净用户可见文本，不追加内部警告。

- `app/src/main/java/com/hirain/aiagent/memory/MemoryCompressor.java`  
  使用 `PromptManager` 渲染 `memory/compress` 模板。

- `app/src/main/java/com/hirain/aiagent/memory/MemoryExtractor.java`  
  使用 `PromptManager` 渲染 `memory/extract` 模板。

- `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`  
  构造函数接收 `PromptManager` 并传给 `MemoryCompressor`、`MemoryExtractor`。

- `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`  
  初始化 `MemoryOrchestrator(this, summaryModel, extractModel, promptManager!!)`。

- `app/src/main/java/com/hirain/aiagent/data/local/LocalPrompt.kt`  
  加 `@Deprecated` 和中文说明，避免误用。

- `docs/overview/prompt-system-design.md`  
  更新设计描述，纠正 assets 热更新边界，补充 selector/assembler/manifest。

- `docs/act_summary/prompt-system-refactoring-summary.md`  
  修正“所有 Prompt 已移除硬编码”的过度表述，说明本次优化后的真实边界。

- `docs/evaluation/prompt-system-evaluation.md`  
  追加本计划落地后的验收状态链接。

---

## Phase 1: 建立测试缝和基础 PromptManager 测试

### Task 1.1: 为 PromptManager 增加 PromptSource

**Files:**
- Create: `app/src/main/java/com/hirain/aiagent/prompt/PromptSource.java`
- Create: `app/src/main/java/com/hirain/aiagent/prompt/AssetPromptSource.java`
- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptManager.java`
- Test: `app/src/test/java/com/hirain/aiagent/prompt/MapPromptSource.java`
- Test: `app/src/test/java/com/hirain/aiagent/prompt/PromptManagerTest.java`

- [ ] **Step 1: 写失败测试，验证 PromptManager 可以使用内存模板源**

```java
package com.hirain.aiagent.prompt;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PromptManagerTest {
    @Test
    public void render_usesPromptSourceAndVariables() {
        MapPromptSource source = new MapPromptSource(Map.of(
                "user/vehicle_status", "车辆状态：{{vehicle_status}}"
        ));
        PromptManager manager = new PromptManager(source);

        String rendered = manager.render("user/vehicle_status",
                Map.of("vehicle_status", "{\"车速\":0}"));

        assertEquals("车辆状态：{\"车速\":0}", rendered);
    }
}
```

- [ ] **Step 2: 添加测试用 MapPromptSource**

```java
package com.hirain.aiagent.prompt;

import java.io.IOException;
import java.util.Map;

final class MapPromptSource implements PromptSource {
    private final Map<String, String> templates;

    MapPromptSource(Map<String, String> templates) {
        this.templates = templates;
    }

    @Override
    public String loadTemplate(String templateName) throws IOException {
        String value = templates.get(templateName);
        if (value == null) {
            throw new IOException("missing template: " + templateName);
        }
        return value;
    }

    @Override
    public String loadManifest() throws IOException {
        String value = templates.get("index");
        if (value == null) {
            throw new IOException("missing manifest");
        }
        return value;
    }
}
```

- [ ] **Step 3: 运行单测并确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.prompt.PromptManagerTest"`

Expected: 编译失败，提示 `PromptSource` 或 `PromptManager(PromptSource)` 不存在。

- [ ] **Step 4: 新增 PromptSource 接口**

```java
package com.hirain.aiagent.prompt;

import java.io.IOException;

/**
 * Prompt 原文来源。
 * 设计原因：PromptManager 只负责模板解析和渲染，不直接绑定 Android assets，
 * 这样 JVM 单元测试可以使用内存模板源验证渲染、缓存和变量校验逻辑。
 */
public interface PromptSource {
    String loadTemplate(String templateName) throws IOException;
    String loadManifest() throws IOException;
}
```

- [ ] **Step 5: 新增 AssetPromptSource**

```java
package com.hirain.aiagent.prompt;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 从 Android assets 读取 Prompt 文件。
 * 模板路径固定为 assets/prompts/{templateName}.txt，清单路径固定为 assets/prompts/index.json。
 */
public final class AssetPromptSource implements PromptSource {
    private static final String PROMPT_BASE = "prompts/";

    private final Context context;

    public AssetPromptSource(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String loadTemplate(String templateName) throws IOException {
        return readAsset(PROMPT_BASE + templateName + ".txt");
    }

    @Override
    public String loadManifest() throws IOException {
        return readAsset(PROMPT_BASE + "index.json");
    }

    private String readAsset(String filePath) throws IOException {
        try (InputStream is = context.getAssets().open(filePath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
```

- [ ] **Step 6: 修改 PromptManager 构造函数**

关键改动：

```java
private final PromptSource promptSource;

public PromptManager(Context context) {
    this(new AssetPromptSource(context));
}

PromptManager(PromptSource promptSource) {
    this.promptSource = promptSource;
}
```

`loadTemplate()` 中把 `context.getAssets().open(...)` 替换为：

```java
String content = promptSource.loadTemplate(templateName);
return PromptTemplate.from(content);
```

- [ ] **Step 7: 运行单测并确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.prompt.PromptManagerTest"`

Expected: `BUILD SUCCESSFUL`，`PromptManagerTest` 通过。

### Task 1.2: 增加 PromptSelector 纯 JVM 测试

**Files:**
- Test: `app/src/test/java/com/hirain/aiagent/prompt/PromptSelectorTest.java`
- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptSelector.java`

- [ ] **Step 1: 写失败测试覆盖 null、其他、有效场景**

```java
package com.hirain.aiagent.prompt;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PromptSelectorTest {
    @Test
    public void sceneSelector_returnsDefaultForNullAndInvalidScene() {
        PromptSelector selector = new PromptSelector.ScenePromptSelector();

        assertEquals(PromptConstants.SYSTEM_ASSISTANT_DEFAULT, selector.select(null));
        assertEquals(PromptConstants.SYSTEM_ASSISTANT_DEFAULT, selector.select(Map.of("scene_type", "其他")));
        assertEquals(PromptConstants.SYSTEM_ASSISTANT_DEFAULT, selector.select(Map.of("scene_type", "无效场景")));
    }

    @Test
    public void sceneSelector_returnsScenePromptForValidSceneType() {
        PromptSelector selector = new PromptSelector.ScenePromptSelector();

        assertEquals(PromptConstants.SYSTEM_ASSISTANT_SCENE,
                selector.select(Map.of("scene_type", "雨雪天气")));
    }
}
```

- [ ] **Step 2: 运行测试确认当前 null case 失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.prompt.PromptSelectorTest"`

Expected: 当前 `ScenePromptSelector.select(null)` 抛出 `NullPointerException`。

- [ ] **Step 3: 修复 PromptSelector null context**

```java
if (context == null || context.isEmpty()) {
    return PromptConstants.SYSTEM_ASSISTANT_DEFAULT;
}
Object value = context.get("scene_type");
String sceneType = value instanceof String ? (String) value : null;
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.prompt.PromptSelectorTest"`

Expected: `BUILD SUCCESSFUL`。

---

## Phase 2: 补齐 PromptSelector 闭环

### Task 2.1: AgentConfig 接入 PromptSelector

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentConfig.java`
- Test: `app/src/test/java/com/hirain/aiagent/core/AgentConfigPromptTest.java`

- [ ] **Step 1: 写失败测试，验证默认 selector 与固定模板兼容**

```java
package com.hirain.aiagent.core;

import com.hirain.aiagent.prompt.PromptConstants;

import org.junit.Test;

import java.time.Duration;
import java.util.Map;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class AgentConfigPromptTest {
    @Test
    public void build_createsDefaultSelectorFromSystemPromptTemplateName() {
        AgentConfig config = AgentConfig.builder("chat")
                .systemPromptTemplateName(PromptConstants.SYSTEM_ASSISTANT_DEFAULT)
                .modelCaller(request -> ChatResponse.builder().aiMessage(AiMessage.from("ok")).build())
                .toolExecutor(request -> "{}")
                .terminator((ctx, response) -> true)
                .resultCollector((response, ctx) -> AgentResult.success("ok", 1, 1, java.util.List.of()))
                .timeout(Duration.ofSeconds(1))
                .build();

        assertNotNull(config.systemPromptSelector());
        assertEquals(PromptConstants.SYSTEM_ASSISTANT_DEFAULT,
                config.systemPromptSelector().select(Map.of()));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentConfigPromptTest"`

Expected: 编译失败，提示 `systemPromptSelector()` 不存在。

- [ ] **Step 3: 修改 AgentConfig 字段和 Builder**

新增字段：

```java
private final PromptSelector systemPromptSelector;
```

新增读取器：

```java
public PromptSelector systemPromptSelector() { return systemPromptSelector; }
```

Builder 新增：

```java
private PromptSelector systemPromptSelector;

public Builder systemPromptSelector(PromptSelector v) {
    this.systemPromptSelector = v;
    return this;
}
```

`build()` 中兼容旧配置：

```java
if (systemPromptSelector == null) {
    if (systemPromptTemplateName == null) {
        throw new IllegalStateException("systemPromptTemplateName or systemPromptSelector is required");
    }
    systemPromptSelector = context -> systemPromptTemplateName;
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.AgentConfigPromptTest"`

Expected: `BUILD SUCCESSFUL`。

### Task 2.2: Orchestrator 使用 selector 选择 SystemPrompt

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopContext.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Test: `app/src/test/java/com/hirain/aiagent/prompt/PromptAssemblerTest.java`

- [ ] **Step 1: 先创建 PromptAssembler 测试，锁定 selector 上下文**

```java
package com.hirain.aiagent.prompt;

import com.hirain.aiagent.core.AgentLoopContext;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PromptAssemblerTest {
    @Test
    public void selectSystemTemplate_usesSceneTypeFromExtraContext() {
        PromptSelector selector = new PromptSelector.ScenePromptSelector();
        AgentLoopContext ctx = new AgentLoopContext("", "scene",
                Map.of("scene_type", "雨雪天气"));

        String template = PromptAssembler.selectSystemTemplate(selector, ctx);

        assertEquals(PromptConstants.SYSTEM_ASSISTANT_SCENE, template);
    }
}
```

- [ ] **Step 2: 给 AgentLoopContext 增加只读上下文快照**

```java
public Map<String, Object> contextDataSnapshot() {
    return Collections.unmodifiableMap(new HashMap<>(contextData));
}
```

设计说明：selector 需要读取 `scene_type`、`user_id`、trace context 等额外上下文，但不能直接暴露可变 `contextData`。

- [ ] **Step 3: 新增最小 PromptAssembler 静态选择方法**

```java
package com.hirain.aiagent.prompt;

import com.hirain.aiagent.core.AgentLoopContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Prompt 组装入口。
 * 当前阶段先负责把 AgentLoopContext 转换为 selector 可消费的上下文；
 * 后续阶段再扩展为多片段组装。
 */
public final class PromptAssembler {
    private PromptAssembler() {}

    public static String selectSystemTemplate(PromptSelector selector, AgentLoopContext ctx) {
        Map<String, Object> selectionContext = new HashMap<>();
        if (ctx != null) {
            selectionContext.put("persona_id", ctx.personaId());
            selectionContext.put("user_input", ctx.userInput());
            selectionContext.putAll(ctx.contextDataSnapshot());
        }
        return selector.select(selectionContext);
    }
}
```

- [ ] **Step 4: 修改 AgentLoopOrchestrator.injectSystemPrompt 签名**

把调用点从：

```java
injectSystemPrompt(userId);
```

改为：

```java
injectSystemPrompt(userId, ctx);
```

把方法签名改为：

```java
private void injectSystemPrompt(String userId, AgentLoopContext ctx)
```

把模板选择改为：

```java
String templateName = PromptAssembler.selectSystemTemplate(
        config.systemPromptSelector(), ctx);
String basePrompt = promptManager.render(templateName);
```

- [ ] **Step 5: 修改 AgentConfigFactory 配置 selector**

chat persona：

```java
.systemPromptTemplateName(PromptConstants.SYSTEM_ASSISTANT_DEFAULT)
.systemPromptSelector(new PromptSelector.DefaultPromptSelector())
```

scene persona：

```java
.systemPromptTemplateName(PromptConstants.SYSTEM_ASSISTANT_SCENE)
.systemPromptSelector(new PromptSelector.ScenePromptSelector())
```

vision persona：

```java
.systemPromptTemplateName(PromptConstants.TASK_FRONT_VIEW_QA)
.systemPromptSelector(context -> PromptConstants.TASK_FRONT_VIEW_QA)
```

- [ ] **Step 6: 运行相关测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.prompt.PromptAssemblerTest" --tests "com.hirain.aiagent.core.AgentConfigPromptTest" --tests "com.hirain.aiagent.prompt.PromptSelectorTest"
```

Expected: 全部通过。

---

## Phase 3: 修复小问题并统一 Prompt 管理范围

### Task 3.1: 外部化记忆压缩和记忆提取 Prompt

**Files:**
- Add: `app/src/main/assets/prompts/memory/compress.txt`
- Add: `app/src/main/assets/prompts/memory/extract.txt`
- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryCompressor.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryExtractor.java`
- Modify: `app/src/main/java/com/hirain/aiagent/memory/MemoryOrchestrator.java`
- Modify: `app/src/main/java/com/hirain/aiagent/AIAgentService.kt`
- Test: `app/src/test/java/com/hirain/aiagent/memory/MemoryPromptExternalizationTest.java`

- [ ] **Step 1: 创建 memory/compress.txt**

```text
请对以下对话历史进行摘要，保留所有关键信息：
- 用户的偏好和要求
- AI 已经执行的操作和结果
- 未解决的问题
- 重要的上下文（位置、时间、车辆状态等）

对话历史：
{{messages}}

摘要（请用中文，控制在 500 字以内）：
```

- [ ] **Step 2: 创建 memory/extract.txt**

```text
分析以下对话，提取值得长期记忆的信息。只提取明确表达的信息，不要推测。

需提取的类型：
- preference: 用户的明确偏好（"我喜欢…"、"调高一点"）
- fact: 用户陈述的事实（"我在去机场的路上"）
- habit: 用户的习惯模式（同类操作出现多次）
- rule: 用户明确要求的规则（"永远不要在我开车时说话"）

对话：
用户：{{user_message}}
AI：{{ai_response}}

输出 JSON 数组（可为空数组）：
[{"category": "...", "key": "...", "value": "...", "confidence": 0.0-1.0}]
```

- [ ] **Step 3: 增加 PromptConstants 常量**

```java
public static final String MEMORY_COMPRESS = "memory/compress";
public static final String MEMORY_EXTRACT = "memory/extract";
```

- [ ] **Step 4: 写失败测试验证 MemoryCompressor 使用外部模板**

测试思路：用假的 `ChatModel` 捕获收到的 prompt，断言 prompt 来自 `MapPromptSource` 的模板内容，而不是源码硬编码。

```java
@Test
public void compressor_rendersExternalPromptTemplate() {
    MapPromptSource source = new MapPromptSource(Map.of(
            "memory/compress", "压缩模板：{{messages}}"
    ));
    PromptManager promptManager = new PromptManager(source);
    CapturingChatModel model = new CapturingChatModel("摘要");
    MemoryCompressor compressor = new MemoryCompressor(model, promptManager);

    String prompt = compressor.renderCompressionPrompt(List.of(UserMessage.from("你好")));

    assertTrue(prompt.startsWith("压缩模板："));
}
```

实施时新增包内可见方法 `String renderCompressionPrompt(List<ChatMessage> messages)`，生产路径 `summarize(...)` 和测试都复用这个方法。这样不需要暴露测试专用 public API，也避免测试必须触发 4000 token 压缩阈值。

- [ ] **Step 5: 修改 MemoryCompressor 构造函数**

```java
private final PromptManager promptManager;

public MemoryCompressor(ChatModel summaryModel, PromptManager promptManager) {
    this.summaryModel = summaryModel;
    this.promptManager = promptManager;
}
```

把：

```java
String prompt = compressionPrompt.replace("{{messages}}", dialogText.toString());
```

改为：

```java
String prompt = promptManager.render(PromptConstants.MEMORY_COMPRESS,
        Map.of("messages", dialogText.toString()));
```

- [ ] **Step 6: 修改 MemoryExtractor 构造函数**

```java
private final PromptManager promptManager;

public MemoryExtractor(ChatModel extractModel, PromptManager promptManager) {
    this.extractModel = extractModel;
    this.promptManager = promptManager;
}
```

把 `.replace(...)` 链改为：

```java
String prompt = promptManager.render(PromptConstants.MEMORY_EXTRACT,
        Map.of(
                "user_message", userMessage,
                "ai_response", aiResponse != null ? aiResponse : ""
        ));
```

- [ ] **Step 7: 修改 MemoryOrchestrator 构造函数**

```java
public MemoryOrchestrator(Context context, ChatModel summaryModel,
                          ChatModel extractModel, PromptManager promptManager) {
    ...
    this.compressor = new MemoryCompressor(summaryModel, promptManager);
    this.extractor = new MemoryExtractor(extractModel, promptManager);
}
```

- [ ] **Step 8: 修改 AIAgentService 初始化**

```kotlin
memoryOrchestrator = MemoryOrchestrator(this, summaryModel, extractModel, promptManager!!)
```

- [ ] **Step 9: 运行记忆相关测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.memory.*"
```

Expected: 既有 memory trace 测试和新外部化测试全部通过。

### Task 3.2: 消除长期记忆重复注入

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Test: `app/src/test/java/com/hirain/aiagent/core/AgentConfigPromptTest.java`

- [ ] **Step 1: 增加测试确认 chat persona 不再配置 MemoryPreProcessor**

测试只检查配置结构，不启动真实模型。若 `AgentConfigFactory.createChatPersona()` 构造真实模型导致依赖 BuildConfig，可改为新增包内 helper 或直接检查重构后的 builder 测试。

断言目标：

```java
assertFalse(config.preProcessors().stream()
        .anyMatch(pp -> pp instanceof MemoryPreProcessor));
```

- [ ] **Step 2: 从 chat persona preProcessors 移除 MemoryPreProcessor**

从：

```java
.preProcessors(List.of(
        new MemoryPreProcessor(memoryOrchestrator),
        new VehicleStatusPreProcessor(promptManager, statusProvider),
        new TimeContextPreProcessor()))
```

改为：

```java
.preProcessors(List.of(
        new VehicleStatusPreProcessor(promptManager, statusProvider),
        new TimeContextPreProcessor(promptManager)))
```

设计说明：长期记忆继续由 `MemoryOrchestrator.prepareSystemPrompt()` 注入 SystemMessage，避免同一内容同时进入 system 和 transient user 层。

### Task 3.3: 当前时间上下文模板化

**Files:**
- Add: `app/src/main/assets/prompts/context/current_time.txt`
- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptConstants.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/preprocessor/TimeContextPreProcessor.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Test: `app/src/test/java/com/hirain/aiagent/prompt/PromptManagerTest.java`

- [ ] **Step 1: 创建模板**

```text
当前时间：{{current_time}}
```

- [ ] **Step 2: 增加常量**

```java
public static final String CONTEXT_CURRENT_TIME = "context/current_time";
```

- [ ] **Step 3: 修改 TimeContextPreProcessor 构造函数**

```java
private final PromptManager promptManager;

public TimeContextPreProcessor(PromptManager promptManager) {
    this.promptManager = promptManager;
}
```

`prepare()` 改为：

```java
String rendered = promptManager.render(PromptConstants.CONTEXT_CURRENT_TIME,
        Map.of("current_time", sdf.format(new Date())));
return List.of(UserMessage.from(rendered));
```

- [ ] **Step 4: 更新 AgentConfigFactory 调用**

```java
new TimeContextPreProcessor(promptManager)
```

### Task 3.4: 清理 VL 用户可见警告

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/tools/vision/vl/VlManager.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/postprocessor/VlWarningPostProcessor.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/factory/AgentConfigFactory.java`
- Test: `app/src/test/java/com/hirain/aiagent/prompt/PromptManagerTest.java`

- [ ] **Step 1: 确认产品策略**

默认策略：内部 VL 记忆失效警告不返回给用户，不进行 TTS 播报。若用户希望保留可见提示，本任务改为把提示改短并仅在 debug 开关下附加。

- [ ] **Step 2: 修改 VlManager.frontCameraInteractionPositive**

把：

```java
String warning = promptManager.render(PromptConstants.MSG_VL_WARNING);
return response + warning;
```

改为：

```java
return response;
```

- [ ] **Step 3: 修改 vision persona postProcessors**

从：

```java
.postProcessors(List.of(new VlWarningPostProcessor(promptManager)))
```

改为：

```java
.postProcessors(List.of(new NoOpPostProcessor()))
```

- [ ] **Step 4: 保留模板但改用途说明**

`messages/vl_warning.txt` 暂时保留，作为历史兼容和后续内部上下文注入候选。`index.json` 中标记 `visibleToUser: false`。

### Task 3.5: 标注 LocalPrompt.kt 为历史遗留

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/data/local/LocalPrompt.kt`

- [ ] **Step 1: 添加废弃注解和中文说明**

```kotlin
@Deprecated(
    message = "历史实验性 Prompt，当前主流程已迁移到 assets/prompts/ 和 PromptManager。不要在新代码中引用。",
    level = DeprecationLevel.WARNING
)
object LocalPrompt {
    ...
}
```

- [ ] **Step 2: 搜索引用确认未接入主流程**

Run:

```powershell
Get-ChildItem -Path app\src\main\java -Recurse -File | Select-String -Pattern 'LocalPrompt'
```

Expected: 只有 `LocalPrompt.kt` 自身和可能的 import；如果发现主流程引用，停止并重新评估迁移策略。

---

## Phase 4: 增加清单、版本管理和动态组装层

### Task 4.1: 新增 prompts/index.json

**Files:**
- Add: `app/src/main/assets/prompts/index.json`
- Add: `app/src/main/java/com/hirain/aiagent/prompt/PromptTemplateSpec.java`
- Add: `app/src/main/java/com/hirain/aiagent/prompt/PromptManifest.java`
- Test: `app/src/test/java/com/hirain/aiagent/prompt/PromptManifestTest.java`

- [ ] **Step 1: 创建初始清单**

```json
{
  "schema_version": 1,
  "templates": [
    {
      "name": "system/assistant_default",
      "version": "1.0.0",
      "type": "system",
      "personas": ["chat"],
      "required_variables": [],
      "visible_to_user": false,
      "description": "默认车载 AI 助手系统提示词"
    },
    {
      "name": "system/assistant_scene",
      "version": "1.0.0",
      "type": "system",
      "personas": ["scene"],
      "required_variables": [],
      "visible_to_user": false,
      "description": "场景服务系统提示词"
    },
    {
      "name": "task/scene_recognition",
      "version": "1.0.0",
      "type": "task",
      "personas": ["scene_match"],
      "required_variables": [],
      "visible_to_user": false,
      "description": "场景识别 VLM 输出 JSON 的任务提示词"
    },
    {
      "name": "task/front_view_qa",
      "version": "1.0.0",
      "type": "task",
      "personas": ["vision_qa"],
      "required_variables": [],
      "visible_to_user": false,
      "description": "前向视野问答系统提示词"
    },
    {
      "name": "user/vehicle_status",
      "version": "1.0.0",
      "type": "context",
      "personas": ["chat", "scene"],
      "required_variables": ["vehicle_status"],
      "visible_to_user": false,
      "description": "车辆状态上下文注入模板"
    },
    {
      "name": "user/scene_description",
      "version": "1.0.0",
      "type": "context",
      "personas": ["scene"],
      "required_variables": ["scene_description"],
      "visible_to_user": false,
      "description": "场景描述上下文注入模板"
    },
    {
      "name": "user/active_control",
      "version": "1.0.0",
      "type": "instruction",
      "personas": ["scene"],
      "required_variables": [],
      "visible_to_user": false,
      "description": "主动控车任务指令"
    },
    {
      "name": "user/summarize",
      "version": "1.0.0",
      "type": "task",
      "personas": ["scene"],
      "required_variables": ["first_part", "second_part"],
      "visible_to_user": false,
      "description": "场景回复合并摘要模板"
    },
    {
      "name": "messages/vl_warning",
      "version": "1.0.0",
      "type": "message",
      "personas": ["vision_qa"],
      "required_variables": [],
      "visible_to_user": false,
      "description": "VL 记忆失效内部提示，默认不直接返回给用户"
    },
    {
      "name": "memory/compress",
      "version": "1.0.0",
      "type": "memory",
      "personas": ["chat"],
      "required_variables": ["messages"],
      "visible_to_user": false,
      "description": "会话历史压缩模板"
    },
    {
      "name": "memory/extract",
      "version": "1.0.0",
      "type": "memory",
      "personas": ["chat"],
      "required_variables": ["user_message", "ai_response"],
      "visible_to_user": false,
      "description": "长期记忆提取模板"
    },
    {
      "name": "context/current_time",
      "version": "1.0.0",
      "type": "context",
      "personas": ["chat"],
      "required_variables": ["current_time"],
      "visible_to_user": false,
      "description": "当前时间上下文模板"
    }
  ]
}
```

- [ ] **Step 2: 实现 PromptTemplateSpec**

字段：

```java
private final String name;
private final String version;
private final String type;
private final List<String> personas;
private final List<String> requiredVariables;
private final boolean visibleToUser;
private final String description;
```

提供只读 getter，不提供 setter。

- [ ] **Step 3: 实现 PromptManifest**

核心 API：

```java
public static PromptManifest load(PromptSource source)
public PromptTemplateSpec get(String templateName)
public boolean contains(String templateName)
public List<PromptTemplateSpec> templates()
public void validateVariables(String templateName, Map<String, Object> variables)
```

`validateVariables` 规则：

- manifest 中不存在模板：抛出 `IllegalArgumentException("Prompt template is not declared in manifest: " + templateName)`。
- 缺少变量：抛出 `IllegalArgumentException("Missing prompt variable '" + key + "' for template: " + templateName)`。
- 允许传入额外变量，避免 LangChain4j 内置变量或未来扩展导致误报。

- [ ] **Step 4: 写 PromptManifestTest**

测试覆盖：

```java
@Test
public void load_parsesTemplateMetadata()

@Test
public void validateVariables_throwsForMissingRequiredVariable()

@Test
public void validateVariables_allowsExtraVariables()
```

- [ ] **Step 5: 运行测试**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.prompt.PromptManifestTest"`

Expected: `BUILD SUCCESSFUL`。

### Task 4.2: PromptManager 接入 manifest 校验

**Files:**
- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptManager.java`
- Test: `app/src/test/java/com/hirain/aiagent/prompt/PromptManagerTest.java`

- [ ] **Step 1: 添加缺少变量失败测试**

```java
@Test(expected = IllegalArgumentException.class)
public void render_throwsWhenRequiredVariableMissing() {
    String manifest = "{\"schema_version\":1,\"templates\":[{\"name\":\"user/vehicle_status\",\"version\":\"1.0.0\",\"type\":\"context\",\"personas\":[\"chat\"],\"required_variables\":[\"vehicle_status\"],\"visible_to_user\":false,\"description\":\"车辆状态\"}]}";
    PromptManager manager = new PromptManager(new MapPromptSource(Map.of(
            "index", manifest,
            "user/vehicle_status", "车辆状态：{{vehicle_status}}"
    )));

    manager.render("user/vehicle_status", Map.of());
}
```

- [ ] **Step 2: PromptManager 增加 manifest 懒加载**

```java
private volatile PromptManifest manifest;

private PromptManifest manifest() {
    PromptManifest local = manifest;
    if (local == null) {
        synchronized (this) {
            local = manifest;
            if (local == null) {
                local = PromptManifest.load(promptSource);
                manifest = local;
            }
        }
    }
    return local;
}
```

- [ ] **Step 3: render 前校验变量**

```java
manifest().validateVariables(templateName, variables);
```

兼容策略：如果 `index.json` 不存在，生产代码可以选择 fail-fast；本计划推荐 fail-fast，因为清单是规范化 Prompt 系统的一部分。

- [ ] **Step 4: reload 同时清 manifest**

```java
public void reload() {
    cache.clear();
    manifest = null;
}
```

### Task 4.3: PromptAssembler 扩展为动态组装结果

**Files:**
- Add: `app/src/main/java/com/hirain/aiagent/prompt/PromptAssembly.java`
- Modify: `app/src/main/java/com/hirain/aiagent/prompt/PromptAssembler.java`
- Modify: `app/src/main/java/com/hirain/aiagent/core/AgentLoopOrchestrator.java`
- Test: `app/src/test/java/com/hirain/aiagent/prompt/PromptAssemblerTest.java`

- [ ] **Step 1: 新增 PromptAssembly**

```java
public final class PromptAssembly {
    private final String text;
    private final String primaryTemplateName;
    private final String primaryTemplateVersion;
    private final List<String> fragments;

    public PromptAssembly(String text, String primaryTemplateName,
                          String primaryTemplateVersion, List<String> fragments) {
        this.text = text;
        this.primaryTemplateName = primaryTemplateName;
        this.primaryTemplateVersion = primaryTemplateVersion;
        this.fragments = List.copyOf(fragments);
    }

    public String text() { return text; }
    public String primaryTemplateName() { return primaryTemplateName; }
    public String primaryTemplateVersion() { return primaryTemplateVersion; }
    public List<String> fragments() { return fragments; }
}
```

- [ ] **Step 2: PromptAssembler 增加 assembleSystemPrompt**

```java
public static PromptAssembly assembleSystemPrompt(AgentConfig config,
                                                  AgentLoopContext ctx,
                                                  String userId,
                                                  PromptManager promptManager,
                                                  MemoryOrchestrator memoryOrchestrator) {
    String templateName = selectSystemTemplate(config.systemPromptSelector(), ctx);
    String basePrompt = promptManager.render(templateName);
    String finalPrompt = memoryOrchestrator != null
            ? memoryOrchestrator.prepareSystemPrompt(userId, basePrompt)
            : basePrompt;
    PromptTemplateSpec spec = promptManager.manifest().get(templateName);
    return new PromptAssembly(finalPrompt, templateName, spec.version(), List.of(templateName));
}
```

如果不想暴露 `PromptManager.manifest()` 为 public，可以新增：

```java
public PromptTemplateSpec templateSpec(String templateName)
```

- [ ] **Step 3: Orchestrator 使用 PromptAssembly**

`injectSystemPrompt` 中改为：

```java
PromptAssembly assembly = PromptAssembler.assembleSystemPrompt(
        config, ctx, userId, promptManager, memoryOrchestrator);
String sysPrompt = assembly.text();
```

第一阶段不强制 trace 记录版本；后续可在 `AgentTraceRecorder.startPromptAssembly` 增加 prompt version 字段。

---

## Phase 5: 文档、完整测试和验收

### Task 5.1: 更新设计文档

**Files:**
- Modify: `docs/overview/prompt-system-design.md`
- Modify: `docs/act_summary/prompt-system-refactoring-summary.md`
- Modify: `docs/evaluation/prompt-system-evaluation.md`

- [ ] **Step 1: 修正 assets 表述**

将“修改 assets 不需要重新编译”改成：

```text
当前模板存放在 Android assets 中，属于 APK 内置资源。它实现的是“Prompt 内容从 Java/Kotlin 源码中分离”，便于源码审查和版本管理；已安装 APK 运行时不会自动读取新的 assets 文件。若需要免发版热更新，需要额外实现可写目录或远端配置加载，并配套签名校验和回滚策略。
```

- [ ] **Step 2: 补充 Prompt 系统新架构**

新增结构：

```text
PromptSource -> PromptManifest -> PromptManager -> PromptSelector -> PromptAssembler -> AgentLoopOrchestrator
```

- [ ] **Step 3: 明确当前模型分工**

写清：

- `SceneMatch` 场景识别使用 VLM：当前实现为 `qwen3-vl-plus`。
- 场景主动控车 persona 使用 LLM：`qwen-flash`。
- 视觉问答使用 VLM：`qwen-vl-max`。

### Task 5.2: 完整单元测试执行

**Files:**
- No code changes.

- [ ] **Step 1: 运行 Prompt 相关测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.prompt.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 运行核心配置和记忆相关测试**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.hirain.aiagent.core.*" --tests "com.hirain.aiagent.memory.*"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 运行全量 JVM 单测**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`。

如果失败：

- 编译错误：先修源码签名或构造函数调用点。
- Android Log JVM 错误：沿用现有 `safeLog*` 模式，不改业务逻辑。
- 依赖下载或网络错误：按环境权限重新运行，不把网络失败误判为代码失败。

### Task 5.3: 人工验证清单

**Files:**
- Create: `docs/evaluation/prompt-system-optimization-verification.md`

- [ ] **Step 1: 记录静态检查结果**

包含：

- `PromptConstants` 每个常量在 `index.json` 有对应条目。
- `index.json` 每个条目有真实 `.txt` 文件。
- 带变量模板都声明了 `required_variables`。
- 记忆压缩和提取不再使用源码内 build prompt。
- chat persona 不再重复注入长期记忆。
- VL 响应不再附加内部警告文本。

- [ ] **Step 2: 记录单测结果**

写入实际命令、退出码、失败数。不能写“应该通过”，必须写真实输出摘要。

- [ ] **Step 3: 记录未覆盖风险**

至少包含：

- 未执行真实 DashScope 调用。
- 未做车机端 APK 安装验证。
- 未做多轮真实语音/TTS 验证。
- `SceneMatch` JSON Schema description 是否外部化暂不作为本轮强制项。

---

## 6. 阶段验收标准

### Phase 1 验收

- `PromptManager` 可用内存模板源测试。
- `PromptSelector` null 和场景分支测试通过。
- 不影响现有 Android assets 构造方式。

### Phase 2 验收

- `AgentConfig` 支持 `systemPromptSelector`。
- `AgentLoopOrchestrator` 的 SystemPrompt 由 selector 决定。
- chat/scene/vision persona 保持原有默认模板行为。

### Phase 3 验收

- `MemoryCompressor` 和 `MemoryExtractor` 使用模板文件。
- chat persona 不再配置 `MemoryPreProcessor`。
- 当前时间上下文模板化。
- VL 用户可见回复不再包含内部失效警告。
- `LocalPrompt.kt` 已标注历史遗留。

### Phase 4 验收

- `index.json` 覆盖所有受管理模板。
- `PromptManager.render()` 对缺失变量 fail-fast。
- `PromptAssembly` 能返回模板名、版本和最终文本。

### Phase 5 验收

- Prompt/core/memory 相关单测通过。
- 全量 `testDebugUnitTest` 通过，或明确记录非代码原因。
- 文档说明与实际实现一致。

## 7. 用户确认点

实施前建议用户确认以下两点：

1. **VL 警告策略**：默认不再返回给用户，也不播报；仅保留为内部 prompt 规则。  
   如果你希望用户看见一条短提示，需要把 Task 3.4 改成 debug/产品可见策略。

2. **`LocalPrompt.kt` 处理方式**：本计划默认只标记废弃，不删除。  
   如果你希望彻底删除，需要先确认没有外部模块、历史 SDK 或文档仍引用它。

## 8. 推荐执行顺序

推荐按 Phase 1 -> Phase 2 -> Phase 3 -> Phase 4 -> Phase 5 顺序执行。不要先做 manifest 或大范围 prompt 迁移，因为没有 Phase 1 的测试缝，后续改动失败时定位成本会明显上升。

如果需要缩短第一轮范围，最小可交付版本是：

1. Phase 1: PromptManager/PromptSelector 测试。
2. Phase 2: PromptSelector 接入闭环。
3. Phase 3.2: 消除长期记忆重复注入。
4. Phase 5.2: 跑相关测试。

这个最小版本能先解决 P0 问题，再进入完整治理。
