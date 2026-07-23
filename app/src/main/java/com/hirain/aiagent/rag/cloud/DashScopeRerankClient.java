package com.hirain.aiagent.rag.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hirain.aiagent.BuildConfig;
import com.hirain.aiagent.runtime.RequestDeadline;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/** DashScope Rerank 客户端：候选序号是唯一外部关联，响应必须完整通过范围/重复校验。 */
public final class DashScopeRerankClient implements RerankClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final RagHttpCallExecutor executor; private final RerankRequestBudgeter budgeter; private final String endpoint; private final long phaseMaxMs; private final int topN;
    public DashScopeRerankClient(RagHttpCallExecutor executor, RerankRequestBudgeter budgeter, String endpoint, long phaseMaxMs, int topN) { this.executor=executor;this.budgeter=budgeter;this.endpoint=endpoint;this.phaseMaxMs=phaseMaxMs;this.topN=topN; }
    @Override public List<RerankItem> rerank(String requestId, String normalizedQuery, List<RerankCandidate> candidates, RequestDeadline deadline) throws RagCloudException {
        if(normalizedQuery==null||normalizedQuery.isBlank())throw new RagCloudException("QUERY_EMPTY");List<RerankCandidate> limited=budgeter.apply(candidates==null?List.of():candidates);if(limited.isEmpty())return List.of();
        JsonObject payload=new JsonObject();payload.addProperty("model","qwen3-rerank");payload.addProperty("query",normalizedQuery);payload.addProperty("top_n",Math.min(topN,limited.size()));JsonArray documents=new JsonArray();for(RerankCandidate candidate:limited){JsonObject item=new JsonObject();item.addProperty("text",candidate.headingPath()+"\n"+candidate.text());documents.add(item);}payload.add("documents",documents);
        Request request=new Request.Builder().url(endpoint).header("Authorization","Bearer "+BuildConfig.DASHSCOPE_API_KEY).post(RequestBody.create(payload.toString(),JSON)).build();
        try(var response=executor.execute(requestId,request,deadline,phaseMaxMs)){if(!response.isSuccessful())throw new RagCloudException(response.code()==401||response.code()==403?"CLOUD_AUTH_FAILURE":"RERANK_UNAVAILABLE");String body=response.body()==null?"":response.body().string();JsonObject root=JsonParser.parseString(body).getAsJsonObject();JsonArray results=root.has("results")?root.getAsJsonArray("results"):root.getAsJsonObject("output").getAsJsonArray("results");if(results==null)throw new RagCloudException("RERANK_RESPONSE_INVALID");List<RerankItem> output=new ArrayList<>();Set<Integer> indexes=new HashSet<>();for(var value:results){JsonObject item=value.getAsJsonObject();int index=item.get("index").getAsInt();double score=item.has("relevance_score")?item.get("relevance_score").getAsDouble():item.get("score").getAsDouble();if(index<0||index>=limited.size()||!indexes.add(index)||!Double.isFinite(score))throw new RagCloudException("RERANK_RESPONSE_INVALID");output.add(new RerankItem(index,score));}return List.copyOf(output);}
        catch(RagCloudException error){throw error;}catch(Exception error){throw new RagCloudException("RERANK_RESPONSE_INVALID",error);}
    }
}
