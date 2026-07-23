package com.hirain.aiagent.rag.store;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
/** 安装新 Bundle 的失败不得使已有 Active Store 从 READY 降级为不可用。 */
public class KnowledgeStoreLifecycleTest {
 @Test public void keepsReadyStoreDuringFailedUpgrade() { KnowledgeStoreLifecycle lifecycle=new KnowledgeStoreLifecycle(); lifecycle.installSucceeded("1.0","scope-a"); lifecycle.installStarted(); assertEquals(KnowledgeStoreState.READY,lifecycle.snapshot().state()); lifecycle.installFailed("BUNDLE_INVALID"); assertEquals(KnowledgeStoreState.READY,lifecycle.snapshot().state()); assertEquals(KnowledgeInstallStatus.FAILED,lifecycle.snapshot().installStatus()); }
 @Test public void entersInstallingWhenNoActiveStore() { KnowledgeStoreLifecycle lifecycle=new KnowledgeStoreLifecycle(); lifecycle.installStarted(); assertEquals(KnowledgeStoreState.INSTALLING,lifecycle.snapshot().state()); }
}
