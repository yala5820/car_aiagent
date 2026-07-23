package com.hirain.aiagent.tools.knowledge;

import com.hirain.aiagent.rag.VehicleKnowledgeService;
import com.hirain.aiagent.rag.model.RagJsonCodec;
import com.hirain.aiagent.rag.policy.KnowledgeInvocationDecision;
import com.hirain.aiagent.rag.policy.VehicleKnowledgeToolResultMapper;
import com.hirain.aiagent.runtime.RequestExecutionContext;
import com.hirain.aiagent.rag.trace.RagTraceRecorder;
import com.hirain.aiagent.rag.trace.RagTraceSnapshot;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import io.opentelemetry.api.trace.Span;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 唯一模型可见的车辆知识入口。系统级身份、Scope、Deadline 与调用计数均从当前请求上下文读取，
 * 因而模型参数不能伪造车型或绕过请求级限制；缺少上下文时绝不创建全局兜底状态。
 */
public final class VehicleKnowledgeTool {
    private final VehicleKnowledgeService service;
    private final VehicleKnowledgeToolResultMapper mapper=new VehicleKnowledgeToolResultMapper();
    private final RagJsonCodec codec=new RagJsonCodec();
    public VehicleKnowledgeTool(VehicleKnowledgeService service) { this.service=service; }
    @Tool(name="searchVehicleKnowledge", value="查询当前车辆的官方使用条件、故障提示、功能限制和版本差异。仅传入需要检索的问题。")
    public String searchVehicleKnowledge(@P("车辆知识检索问题") String query) {
        RequestExecutionContext.State context=RequestExecutionContext.current();
        if(context==null||context.knowledgeRequestState()==null||service==null) return failure("KNOWLEDGE_REQUEST_CONTEXT_MISSING");
        KnowledgeInvocationDecision allowed=context.knowledgeRequestState().tryBeginInvocation(
                context.knowledgeRequestState().currentIteration(),query);
        if(!allowed.allowed()) return failure(allowed.reasonCode());
        RagTraceRecorder trace = new RagTraceRecorder(context.traceSession());
        long now = System.currentTimeMillis();
        Span retrieveSpan = trace.startRetrieve(new RagTraceSnapshot(RagTraceRecorder.queryHash(query), "", true,
                0, List.of(), List.of(), "", 0L, context.deadline().remainingMs(now), ""));
        var result=service.search(context.requestId(),query,context.deadline());
        context.knowledgeRequestState().recordOutcome(result.answerable()?"EVIDENCE":"NO_EVIDENCE",result.retrievalMode()==null?"NONE":result.retrievalMode().name());
        var mapped=mapper.map(result,context.knowledgeRequestState());
        context.knowledgeRequestState().registerCitationEvidence(mapped.evidence());
        trace.finishRetrieve(retrieveSpan, new RagTraceSnapshot(RagTraceRecorder.queryHash(query), "", true,
                result.retrievalEvidence().size(), List.of(), mapped.evidence().stream().map(item -> item.evidenceId()).collect(Collectors.toList()),
                result.retrievalMode() == null ? "" : result.retrievalMode().name(), result.elapsedMs(),
                context.deadline().remainingMs(System.currentTimeMillis()),
                result.failureReasonCode() == null ? "" : result.failureReasonCode().name()));
        return codec.encodeToolResult(mapped);
    }
    private static String failure(String code) { return "{\"schemaVersion\":1,\"status\":\"ERROR\",\"answerable\":false,\"evidence\":[],\"failureReasonCode\":\""+code+"\",\"message\":\"车辆知识请求当前不可执行。\"}"; }
}
