package com.hirain.aiagent.rag.store;
/** 与 Active Store 分离的安装任务状态，避免升级安装覆盖现有 READY 服务。 */
public enum KnowledgeInstallStatus { IDLE, RUNNING, SUCCEEDED, FAILED }
