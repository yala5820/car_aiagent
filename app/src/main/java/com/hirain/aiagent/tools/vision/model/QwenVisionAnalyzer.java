package com.hirain.aiagent.tools.vision.model;

import com.google.gson.Gson;
import com.hirain.aiagent.runtime.RequestCallRegistry;
import com.hirain.aiagent.runtime.RequestExecutionContext;
import com.hirain.aiagent.tools.vision.demo.FrontViewImage;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Base64;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

/** qwen-vl-max 的单次、无记忆结构化分析封装。 */
public final class QwenVisionAnalyzer implements VisionAnalyzer {
    private static final int MAX_OUTPUT_CHARS=16*1024;
    private final ChatModel model; private final RequestCallRegistry calls; private final String systemPrompt;
    public QwenVisionAnalyzer(ChatModel model, RequestCallRegistry calls, String systemPrompt){this.model=model;this.calls=calls;this.systemPrompt=systemPrompt;}
    @Override public VisionAnalysis analyze(String question, FrontViewImage image) throws VisionAnalysisException {
        try {
            String base64=Base64.getEncoder().encodeToString(image.bytes());
            String text=model.chat(SystemMessage.from(systemPrompt), UserMessage.from(
                    TextContent.from(question), ImageContent.from(base64,image.mimeType()))).aiMessage().text();
            if(text==null||text.length()>MAX_OUTPUT_CHARS) throw new VisionAnalysisException("MODEL_OUTPUT_INVALID","empty_or_oversized");
            VisionAnalysis analysis=new Gson().fromJson(text,VisionAnalysis.class);
            if(analysis==null||analysis.summary==null||analysis.summary.isBlank()||analysis.summary.length()>1000) throw new VisionAnalysisException("MODEL_OUTPUT_INVALID","invalid_summary");
            analysis.observations=bounded(analysis.observations); analysis.uncertainties=bounded(analysis.uncertainties);
            return analysis;
        } catch(VisionAnalysisException e){throw e;} catch(Exception e){
            RequestExecutionContext.State ctx=RequestExecutionContext.current();
            if(ctx!=null && calls!=null && calls.isCancellationRequested(ctx.requestId()) && !ctx.deadline().isExpired(System.currentTimeMillis())) throw new VisionAnalysisException("MODEL_CANCELLED","cancelled");
            if((ctx!=null&&ctx.deadline().isExpired(System.currentTimeMillis()))||hasTimeout(e)) throw new VisionAnalysisException("MODEL_TIMEOUT","timeout");
            throw new VisionAnalysisException("MODEL_CALL_FAILED","model_call_failed");
        }
    }
    private static List<String> bounded(List<String> values) throws VisionAnalysisException { if(values==null)return List.of(); if(values.size()>20)throw new VisionAnalysisException("MODEL_OUTPUT_INVALID","too_many_items"); for(String s:values)if(s==null||s.length()>500)throw new VisionAnalysisException("MODEL_OUTPUT_INVALID","invalid_item"); return List.copyOf(values); }
    private static boolean hasTimeout(Throwable e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof SocketTimeoutException)return true;return false;}
}
