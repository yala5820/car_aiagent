package com.hirain.aiagent.rag.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hirain.aiagent.BuildConfig;
import com.hirain.aiagent.runtime.RequestDeadline;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/** DashScope compatible Embedding 客户端：单 Query、单向量、固定 1024 维。 */
public final class DashScopeQueryEmbeddingClient implements QueryEmbeddingClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final RagHttpCallExecutor executor; private final String endpoint; private final long phaseMaxMs;
    public DashScopeQueryEmbeddingClient(RagHttpCallExecutor executor, String baseUrl, long phaseMaxMs) { this.executor=executor; this.endpoint=baseUrl.replaceAll("/$", "")+"/embeddings"; this.phaseMaxMs=phaseMaxMs; }
    @Override public float[] embed(String requestId, String normalizedQuery, RequestDeadline deadline) throws RagCloudException {
        if (normalizedQuery == null || normalizedQuery.isBlank()) throw new RagCloudException("QUERY_EMPTY");
        JsonObject payload=new JsonObject();payload.addProperty("model","text-embedding-v4");payload.addProperty("input",normalizedQuery);
        Request request=new Request.Builder().url(endpoint).header("Authorization","Bearer "+BuildConfig.DASHSCOPE_API_KEY).post(RequestBody.create(payload.toString(),JSON)).build();
        try (var response=executor.execute(requestId,request,deadline,phaseMaxMs)) { if (!response.isSuccessful()) throw new RagCloudException(response.code()==401||response.code()==403?"CLOUD_AUTH_FAILURE":"EMBEDDING_UNAVAILABLE"); String body=response.body()==null?"":response.body().string(); JsonArray data=JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data"); if(data==null||data.size()!=1)throw new RagCloudException("EMBEDDING_RESPONSE_INVALID"); JsonArray embedding=data.get(0).getAsJsonObject().getAsJsonArray("embedding");if(embedding==null||embedding.size()!=1024)throw new RagCloudException("EMBEDDING_DIMENSION_INVALID"); float[] vector=new float[1024];for(int index=0;index<vector.length;index++){double value=embedding.get(index).getAsDouble();if(!Double.isFinite(value))throw new RagCloudException("EMBEDDING_VALUE_INVALID");vector[index]=(float)value;}return vector; }
        catch (RagCloudException error) { throw error; } catch (Exception error) { throw new RagCloudException("EMBEDDING_RESPONSE_INVALID",error); }
    }
}
