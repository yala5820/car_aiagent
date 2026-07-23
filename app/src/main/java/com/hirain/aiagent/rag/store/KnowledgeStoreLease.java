package com.hirain.aiagent.rag.store;

import java.util.concurrent.atomic.AtomicBoolean;
/** 单次查询持有的 Store 引用；close 幂等，避免重复归还导致旧 Store 过早关闭。 */
public final class KnowledgeStoreLease<T> implements AutoCloseable {
 private final T value; private final Runnable release; private final AtomicBoolean closed=new AtomicBoolean();
 KnowledgeStoreLease(T value,Runnable release){this.value=value;this.release=release;}
 public T value(){if(closed.get())throw new IllegalStateException("Store Lease 已关闭");return value;}
 @Override public void close(){if(closed.compareAndSet(false,true))release.run();}
}
