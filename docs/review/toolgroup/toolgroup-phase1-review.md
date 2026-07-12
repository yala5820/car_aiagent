# ToolGroup Interface Improvement — Phase 1 验收审查

**审查日期：** 2026-07-10
**审查范围：** Phase 1 规范选择输入与接口兼容（Task 1.1～1.2）
**审查依据：** `docs/plan_overall/2026-07-10-toolgroup-interface-improvement-plan.md` Phase 1
**审查人：** Claude Code

---

## 一、总体评估

**有条件通过，1 个计划偏离 + 1 个缺失文件需处理。** 核心功能正确：`ToolGroupSelectionInput` 实现完整，接口兼容路径通顺，全量单测通过，全量回归零失败。但存在两个问题：SAM 方向与计划相反，以及测试文件缺失。

---

## 二、本阶段做了什么

本阶段只做了两件事，都在计划范围内：

1. **新增了选择输入对象** `ToolGroupSelectionInput`——把 `IntentResult + userInput` 的两参数调法封装成一个对象，预留了 `inputType`、`userId`、`sessionId`、`personaId` 字段，日后换小 LLM / 子 agent 选择器时不用改方法签名。

2. **升级了 `ToolGroupSelector` 接口**——让新老两种调用方式都能走。旧代码仍然用 `select(intent, userInput)`，新代码可以直接传 `ToolGroupSelectionInput` 对象。`DefaultToolGroupSelector` 同步适配。

---

## 三、文件清单

| 文件 | 状态 | 说明 |
|------|------|------|
| `toolgroup/ToolGroupSelectionInput.java` | ✅ 正确 | 6 字段、不可变、Builder + of() 工厂、null 安全 |
| `toolgroup/ToolGroupSelector.java` | ⚠️ **SAM 方向与计划相反** | 见问题 1 |
| `toolgroup/DefaultToolGroupSelector.java` | ✅ 正确 | 改为实现 `select(ToolGroupSelectionInput)` |
| `test/.../DefaultToolGroupSelectorTest.java` | ✅ 正确 | 9 个测试全覆盖，含新输入对象路径 |
| `test/.../ToolGroupSelectionInputTest.java` | ❌ **不存在** | 见问题 2 |

---

## 四、发现的问题

### 问题 1（计划偏离）：SAM 接口方向与计划相反

**计划要求（Task 1.2）：**
> 保持当前 `select(IntentResult intentResult, String userInput)` 为唯一抽象方法，不改变 Java SAM 签名。
> 新增 default 方法 `select(ToolGroupSelectionInput input)`，内部委托二参抽象方法。

**实际实现：**
> 抽象方法 = `select(ToolGroupSelectionInput input)`
> 默认方法 = `select(IntentResult intentResult, String userInput)`（委托到抽象方法）

SAM 被反转了。

**影响分析：**

经过逐处核对，当前代码中所有生产和测试的调用点都能正确工作：

- `AgentRuntime.selectToolGroupsSafely()` → 调用旧二参签名 → 走默认方法委托 → 正确 ✅
- `AgentRuntimeTest` 中两个 lambda → 都是单参数 `input ->`，匹配新 SAM → 正确 ✅
- `DefaultToolGroupSelectorTest` 全部测试 → 大部分走旧二参默认方法、`select_viaToolGroupSelectionInput_roundTripsInputTypeAndPersona` 走新 SAM → 正确 ✅

**结论：** 当前实现不会造成编译错误或运行时异常，功能覆盖完整。但 SAM 方向与计划相反，存在一个隐患：**如果未来有人按旧 SAM 写 `(intentResult, userInput) -> ...` 二参 lambda，现在会编译失败**。是否接受此偏离，请确认。

### 问题 2（缺失）：`ToolGroupSelectionInputTest.java` 不存在

**计划要求（Task 1.1）：**
> Create: `app/src/test/java/com/hirain/aiagent/toolgroup/ToolGroupSelectionInputTest.java`

**缺失的测试（计划指定 3 个）：**
- `of_usesIntentAndUserInput()`
- `builder_defaultsInputTypeAndPersona()`
- `builder_preservesUserSessionPersona()`

**实际情况：** `DefaultToolGroupSelectorTest.select_viaToolGroupSelectionInput_roundTripsInputTypeAndPersona` 间接覆盖了 Input 对象的 builder 使用，但以下边界没有被覆盖：
- `of()` 工厂方法的 `inputType` 默认 "TEXT"、`personaId` 默认 "chat"
- builder 的 `inputType(null)` / `personaId(null)` 不覆盖默认值
- 单独构造 Input 对象的不可变性

建议补上这些测试。

---

## 五、已验证项

| 检查项 | 状态 |
|--------|------|
| ToolGroupSelectionInput 不可变 | ✅ |
| 不依赖 Android 类型 | ✅ |
| 不依赖 LangChain4j 类型 | ✅ |
| 旧二参调用路径兼容 | ✅ |
| DefaultToolGroupSelector 所有意图映射 | ✅ 9 个测试全覆盖 |
| 弱车载关键词兜底 | ✅ |
| KeywordIntentRouter + Selector 集成 | ✅ |
| 新 Input 对象 round-trip 路径 | ✅ |
| toolgroup.* 单测 | ✅ BUILD SUCCESSFUL |
| 全量单测回归 | ✅ BUILD SUCCESSFUL |

---

## 六、审查结论

**通过，需处理 2 项：**

| 优先级 | 问题 | 处理建议 |
|--------|------|---------|
| **确认** | SAM 方向与计划相反 | 是维持现状（功能没问题），还是翻过来改回去？ |
| **补充** | `ToolGroupSelectionInputTest.java` 缺失 | 补建测试文件，至少覆盖 of() 默认值、builder null 安全、字段透传 |
