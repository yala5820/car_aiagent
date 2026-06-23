package langchain4j.http_client_ok;

import dev.langchain4j.http.client.HttpClientBuilder;
import java.time.Duration;

public class OkHttpClientBuilder implements HttpClientBuilder {
    private  Duration connectTimeout;
    private Duration readTimeout;
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

    @Override
    public OkHttpClient build() {
        return new OkHttpClient(this);
    }
}
