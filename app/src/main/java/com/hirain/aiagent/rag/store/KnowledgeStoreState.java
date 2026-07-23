package com.hirain.aiagent.rag.store;
/** Active Store 生命周期；READY 表示存在可查询数据库，不等同于后台安装是否正在运行。 */
public enum KnowledgeStoreState { UNINITIALIZED, INSTALLING, READY, FAILED, CLOSED }
