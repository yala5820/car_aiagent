package com.hirain.aiagent.rag.policy;
import com.hirain.aiagent.rag.model.*;import java.util.List;
/** 可回答性不依赖单一相关性分数：至少一条适用且具 Locator 的证据，并且请求未取消/超时。 */
public final class AnswerabilityPolicy { public boolean answerable(List<RetrievalEvidence> evidence,boolean cancelled,boolean expired,boolean hardFailure){if(cancelled||expired||hardFailure)return false;for(RetrievalEvidence item:evidence)if(item.applicability()!=Applicability.UNKNOWN&&item.content()!=null&&!item.content().isBlank()&&item.sourceLocator()!=null)return true;return false;} }
