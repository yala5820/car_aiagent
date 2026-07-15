package langchain4j.http_client_ok;

import com.hirain.aiagent.runtime.RequestCallRegistry;
import com.hirain.aiagent.runtime.RequestExecutionContext;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
public class OkHttpClient implements HttpClient {
    private final okhttp3.OkHttpClient delegate;
    private final RequestCallRegistry requestCallRegistry;

    public OkHttpClient(OkHttpClientBuilder builder) {
        okhttp3.OkHttpClient.Builder okBuilder = builder.httpClientBuilder();

        if (builder.connectTimeout() != null) {
            okBuilder.connectTimeout(builder.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
        if (builder.readTimeout() != null) {
            okBuilder.readTimeout(builder.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }

        this.delegate = okBuilder.build();
        this.requestCallRegistry = builder.requestCallRegistry();
    }

    public static OkHttpClientBuilder builder() {
        return new OkHttpClientBuilder();
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        Request okRequest = toOkRequest(request);
        Call call = delegate.newCall(okRequest);
        RequestCallRegistry.Registration registration = null;
        RequestExecutionContext.State executionContext = RequestExecutionContext.current();
        if (executionContext != null) {
            long remainingMs = executionContext.deadline().remainingMs(System.currentTimeMillis());
            if (remainingMs <= 0L) {
                throw new RuntimeException(new SocketTimeoutException("request deadline exceeded"));
            }
            call.timeout().timeout(remainingMs, TimeUnit.MILLISECONDS);
            if (requestCallRegistry != null) {
                registration = requestCallRegistry.register(executionContext.requestId(), call);
            }
        }
        try (Response response = call.execute()) {
            return handleResponse(response);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (registration != null) {
                registration.close();
            }
        }
    }

    @Override
    public void execute(HttpRequest request, dev.langchain4j.http.client.sse.ServerSentEventParser parser, dev.langchain4j.http.client.sse.ServerSentEventListener listener) {
        Request okRequest = toOkRequest(request);
        delegate.newCall(okRequest).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        listener.onError(new HttpException(response.code(), body != null ? body.string() : ""));
                        return;
                    }

                    SuccessfulHttpResponse httpResponse = fromOkResponse(response);
                    listener.onOpen(httpResponse);

                    if (body != null) {
                        try (InputStream inputStream = body.byteStream()) {
                            parser.parse(inputStream, listener);
                            listener.onClose();
                        } catch (IOException e) {
                            listener.onError(e);
                        }
                    } else {
                        listener.onClose();
                    }
                } catch (IOException e) {
                    listener.onError(e);
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                listener.onError(e);
            }
        });
    }

    private SuccessfulHttpResponse handleResponse(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String body = response.body() != null ? response.body().string() : "";
            throw new HttpException(response.code(), body);
        }
        return fromOkResponse(response);
    }

    private Request toOkRequest(HttpRequest request) {
        Request.Builder builder = new Request.Builder().url(request.url());

        switch (request.method().toString()) {
            case "GET": builder.get(); break;
            case "HEAD": builder.head(); break;
            case "POST": builder.post(toRequestBody(request)); break;
            case "PUT": builder.put(toRequestBody(request)); break;
            case "DELETE": builder.delete(toRequestBody(request)); break;
            case "PATCH": builder.patch(toRequestBody(request)); break;
            default: throw new IllegalArgumentException("Unknown method: " + request.method());
        }

        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            String headerName = entry.getKey();
            for (String headerValue : entry.getValue()) {
                builder.addHeader(headerName, headerValue);
            }
        }

        return builder.build();
    }

    private RequestBody toRequestBody(HttpRequest request) {
        if (request.body() == null) return RequestBody.create(new byte[0], null);

        MediaType mediaType = null;
        List<String> contentTypeValues = request.headers().get("Content-Type");
        if (contentTypeValues != null && !contentTypeValues.isEmpty()) {
            mediaType = MediaType.parse(contentTypeValues.get(0));
        }

        return RequestBody.create(request.body(), mediaType);
    }

    private SuccessfulHttpResponse fromOkResponse(Response response) {

        Map<String, List<String>> headers = new CaseInsensitiveHeadersMap();
        Headers responseHeaders = response.headers();
        for (String name : responseHeaders.names()) {
            headers.put(name, responseHeaders.values(name));
        }

        String body = null;
        try {
            if (response.body() != null) {
                body = response.body().string();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        SuccessfulHttpResponse.Builder builder = SuccessfulHttpResponse.builder()
                .body(body)
                .statusCode(response.code())
                .headers(headers);
        return builder.build();
    }

    private static class CaseInsensitiveHeadersMap extends java.util.HashMap<String, List<String>> {
        @Override
        public List<String> put(String key, List<String> value) {
            return super.put(key.toLowerCase(), value);
        }

        @Override
        public List<String> get(Object key) {
            if (key instanceof String) {
                return super.get(((String) key). toLowerCase());
            }
            return super.get(key);
        }
    }
}
