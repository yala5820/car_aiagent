package com.hirain.aiagent.rag.policy;
/** 请求级 Evidence ID 分配器；不使用全局计数器，Phase 3 将由 KnowledgeRequestState 持有。 */
public final class EvidenceIdAllocator { private int next=1; public String nextId(){return "E"+(next++);} }
