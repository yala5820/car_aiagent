package com.hirain.aiagent.rag.cloud;

import com.hirain.aiagent.runtime.RequestCallRegistry;
import com.hirain.aiagent.runtime.RequestDeadline;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 每次云调用按同一绝对 Deadline 缩短超时，并注册到既有取消注册表。 */
public final class RagHttpCallExecutor {
    private final OkHttpClient client; private final RequestCallRegistry calls; private final long finalAnswerReserveMs; private final int maxRetries;
    public RagHttpCallExecutor(OkHttpClient client, RequestCallRegistry calls, long finalAnswerReserveMs) { this(client, calls, finalAnswerReserveMs, 2); }
    public RagHttpCallExecutor(OkHttpClient client, RequestCallRegistry calls, long finalAnswerReserveMs, int maxRetries) { this.client = client; this.calls = calls; this.finalAnswerReserveMs = finalAnswerReserveMs; this.maxRetries = Math.max(0, maxRetries); }
    public Response execute(String requestId, Request request, RequestDeadline deadline, long phaseMaxMs) throws RagCloudException {
        IOException lastNetworkFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            long remaining = deadline.remainingMs(System.currentTimeMillis()) - finalAnswerReserveMs;
            if (remaining <= 0L) throw new RagCloudException("DEADLINE_EXCEEDED"); if (calls.isCancellationRequested(requestId)) throw new RagCloudException("REQUEST_CANCELLED");
            Call call = client.newBuilder().callTimeout(Math.min(remaining, phaseMaxMs), TimeUnit.MILLISECONDS).build().newCall(request);
            try (RequestCallRegistry.Registration ignored = calls.register(requestId, call)) {
                if (call.isCanceled()) throw new RagCloudException("REQUEST_CANCELLED");
                Response response = call.execute();
                if (response.code() >= 500 && attempt < maxRetries) { response.close(); continue; }
                return response;
            } catch (IOException error) { if (calls.isCancellationRequested(requestId)) throw new RagCloudException("REQUEST_CANCELLED", error); lastNetworkFailure = error; }
        }
        throw new RagCloudException("CLOUD_NETWORK_FAILURE", lastNetworkFailure);
    }
}
