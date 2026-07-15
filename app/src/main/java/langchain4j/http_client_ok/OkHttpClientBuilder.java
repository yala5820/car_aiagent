package langchain4j.http_client_ok;

import dev.langchain4j.http.client.HttpClientBuilder;
import com.hirain.aiagent.runtime.RequestCallRegistry;
import java.time.Duration;

public class OkHttpClientBuilder implements HttpClientBuilder {
    private  Duration connectTimeout;
    private Duration readTimeout;
    private RequestCallRegistry requestCallRegistry;
    private final okhttp3.OkHttpClient.Builder clientBuilder;

    public OkHttpClientBuilder() {
        this.clientBuilder = new okhttp3.OkHttpClient.Builder();
    }

    public okhttp3.OkHttpClient.Builder httpClientBuilder() {
        return clientBuilder;
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public OkHttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public OkHttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    /** 注入 TEXT 请求使用的 Call 注册表；未注入时保持普通 HTTP Client 行为。 */
    public OkHttpClientBuilder requestCallRegistry(RequestCallRegistry requestCallRegistry) {
        this.requestCallRegistry = requestCallRegistry;
        return this;
    }

    public RequestCallRegistry requestCallRegistry() {
        return requestCallRegistry;
    }

    @Override
    public OkHttpClient build() {
        return new OkHttpClient(this);
    }
}
