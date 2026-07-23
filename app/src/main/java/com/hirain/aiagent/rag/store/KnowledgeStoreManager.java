package com.hirain.aiagent.rag.store;

/** Active Gateway 的并发门面；安装器通过 activate 切换，检索器仅获取 Lease。 */
public final class KnowledgeStoreManager implements AutoCloseable {
 private final ActiveStoreLeaseManager<KnowledgeStoreGateway> leases=new ActiveStoreLeaseManager<>();
 private final KnowledgeStoreLifecycle lifecycle=new KnowledgeStoreLifecycle();
 public KnowledgeStoreLease<KnowledgeStoreGateway> acquire(){return leases.acquire();}
 public synchronized void activate(KnowledgeStoreGateway gateway,String bundleVersion,String scopeId){leases.activate(gateway);lifecycle.installSucceeded(bundleVersion,scopeId);}
 public synchronized void installStarted(){lifecycle.installStarted();}
 public synchronized void installFailed(String reasonCode){lifecycle.installFailed(reasonCode);}
 public KnowledgeStoreSnapshot snapshot(){return lifecycle.snapshot();}
 @Override public synchronized void close(){leases.close();lifecycle.close();}
}
