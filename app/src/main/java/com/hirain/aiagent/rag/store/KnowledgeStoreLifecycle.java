package com.hirain.aiagent.rag.store;

/**
 * 将安装任务与 Active Store 生命周期分离的纯状态机，便于先测试升级失败不影响旧库的核心不变量。
 */
public final class KnowledgeStoreLifecycle {
    private KnowledgeStoreSnapshot snapshot = new KnowledgeStoreSnapshot(KnowledgeStoreState.UNINITIALIZED, KnowledgeInstallStatus.IDLE, null, null, null);
    public synchronized KnowledgeStoreSnapshot snapshot() { return snapshot; }
    public synchronized void installStarted() {
        snapshot = new KnowledgeStoreSnapshot(snapshot.state() == KnowledgeStoreState.READY ? KnowledgeStoreState.READY : KnowledgeStoreState.INSTALLING,
                KnowledgeInstallStatus.RUNNING, snapshot.bundleVersion(), snapshot.knowledgeScopeId(), null);
    }
    public synchronized void installSucceeded(String bundleVersion, String scopeId) {
        snapshot = new KnowledgeStoreSnapshot(KnowledgeStoreState.READY, KnowledgeInstallStatus.SUCCEEDED, bundleVersion, scopeId, null);
    }
    public synchronized void installFailed(String reasonCode) {
        snapshot = new KnowledgeStoreSnapshot(snapshot.state() == KnowledgeStoreState.READY ? KnowledgeStoreState.READY : KnowledgeStoreState.FAILED,
                KnowledgeInstallStatus.FAILED, snapshot.bundleVersion(), snapshot.knowledgeScopeId(), reasonCode);
    }
    public synchronized void close() { snapshot = new KnowledgeStoreSnapshot(KnowledgeStoreState.CLOSED, snapshot.installStatus(), snapshot.bundleVersion(), snapshot.knowledgeScopeId(), snapshot.failureReason()); }
}
