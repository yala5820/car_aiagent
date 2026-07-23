package com.hirain.aiagent.rag.policy;

import com.hirain.aiagent.intentrouter.IntentResult;

/** 在既有 IntentResult 上叠加知识需求判断，不创建或替代第二个全局意图路由器。 */
public interface KnowledgeNeedDetector {
    KnowledgeIntentDecision decide(String userText, IntentResult intentResult);
}
