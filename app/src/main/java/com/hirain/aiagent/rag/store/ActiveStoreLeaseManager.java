package com.hirain.aiagent.rag.store;

/** 不依赖 ObjectBox 的 Active Holder，供 Manager 使用并可独立验证切换/延迟关闭语义。 */
public final class ActiveStoreLeaseManager<T extends AutoCloseable> implements AutoCloseable {
 private Holder<T> active; private boolean closed;
 public synchronized KnowledgeStoreLease<T> acquire(){if(closed||active==null)throw new IllegalStateException("没有可用知识 Store");active.leases++;Holder<T> holder=active;return new KnowledgeStoreLease<>(holder.value,()->release(holder));}
 public synchronized void activate(T next){if(closed)throw new IllegalStateException("Store Manager 已关闭");Holder<T> previous=active;active=new Holder<>(next);if(previous!=null){previous.retired=true;closeIfIdle(previous);}}
 private synchronized void release(Holder<T> holder){holder.leases--;closeIfIdle(holder);}
 private void closeIfIdle(Holder<T> holder){if(holder.retired&&holder.leases==0)try{holder.value.close();}catch(Exception ignored){}}
 @Override public synchronized void close(){if(closed)return;closed=true;if(active!=null){active.retired=true;closeIfIdle(active);active=null;}}
 private static final class Holder<T extends AutoCloseable>{final T value;int leases;boolean retired;Holder(T value){this.value=value;}}
}
