package com.hirain.aiagent.rag.store;
/** 面向 Runtime/Trace 的不可变状态快照；失败原因是受控码，不包含异常和绝对路径。 */
public record KnowledgeStoreSnapshot(KnowledgeStoreState state, KnowledgeInstallStatus installStatus,
                                     String bundleVersion, String knowledgeScopeId, String failureReason) { }
