# Conversation Management Phase 4 验收审查

**审查日期：** 2026-07-07
**审查范围：** Phase 4 请求取消（ActiveRequestRegistry + RuntimeResult cancelled + AIAgentService 集成）
**审查人：** Claude Code

---

## 一、总体评估

**发现 1 个中危竞态问题，其余实现质量良好。** `ActiveRequest` 使用 `AtomicReference<TerminalState>` 实现 CAS 终态抢占，比计划的 `AtomicBoolean` 方案更完善。RuntimeResult CANCELLED 工厂和 ResponseMapper 映射正确。ActiveRequestRegistry 的 `finishedRequests` cache + 60s TTL 实现正确。97 个单测全部通过。

---

## 二、中危问题

### 2.1 取消响应可能丢失的竞态条件

**位置：** `AIAgentService.kt:608-613` 与 `AIAgentService.kt:515-522`

**触发路径：**

```
取消 AIDL (Binder 线程)                    Worker 线程 (mWorkHandler)
│                                          │
├─ cancel("req-1") → CAS CANCELLED ✅     │
│  → 返回 ACCEPTED                         │
│                                          ├─ isCancelled → true (CAS 已生效)
│                                          ├─ finish("req-1")  ← 从 map 移除
│                                          └─ return@post (不发送响应)
│                                          │
├─ get("req-1") → null ❌                 │
│  → ?.let 不执行 → 不发送 CANCELLED 响应  │
│                                          │
└─ 用户: cancel 返回 ACCEPTED              │
  但 listener 永远收不到 CANCELLED 响应     │
```

**根因：** Worker 线程在 `isCancelled` 路径中（line 612）主动调用了 `activeRequestRegistry.finish()`，但取消处理线程在 finish 之后才执行 `activeRequestRegistry.get(id)`（line 515），导致 get 返回 null，CANCELLED 响应不被发送。

**修复方式：** Worker 的 `isCancelled` 路径移除 `activeRequestRegistry.finish()` 调用，将 cleanup 责任留给终态抢占成功的唯一一方：

```kotlin
// 修复前 (line 608-613):
if (activeRequest.isCancelled) {
    activeTimeouts.remove(runtimeSession.requestId())?.let {
        mainHandler.removeCallbacks(it)
    }
    activeRequestRegistry.finish(runtimeSession.requestId())  // ← 移到 cancel handler
    return@post
}

// 修复后:
if (activeRequest.isCancelled) {
    activeTimeouts.remove(runtimeSession.requestId())?.let {
        mainHandler.removeCallbacks(it)
    }
    return@post
}
```

取消路径已在成功 CAS 后调用 `activeRequestRegistry.finish(id)`（line 522），因此 worker 不再需要调用 finish。

---

## 三、其他验证结果

| 检查项 | 结果 |
|--------|------|
| ActiveRequest 使用 AtomicReference CAS 终态抢占 | ✅ |
| ActiveRequestRegistry 含 finishedRequests cache + 60s TTL | ✅ |
| Register 时清理同 requestId 的 finished cache | ✅ |
| RuntimeResult.cancelled 工厂方法 | ✅ |
| RuntimeResponseMapper 的 CANCELLED 映射分支 | ✅ |
| AgentRuntime.cancelledResult() 正确传递 session 元信息 | ✅ |
| Cancel AIDL 移除 timeout runnable + stopTTS | ✅ |
| timeout runnable 使用 tryComplete 抢占终态 | ✅ |
| Worker success/failure 使用 tryComplete 抢占终态 | ✅ |
| 编译 BUILD SUCCESSFUL | ✅ |
| 全部 97 个单测通过 | ✅ |
| 5 个受限文件零 diff | ✅ |

---

## 四、审查结论

**条件通过。** 中危竞态问题 2.1 必须修复：移除 worker `isCancelled` 路径中的 `finish()` 调用（line 612），将 cleanup 留给取消 handler 独占。修复后可以进入 Phase 5。
