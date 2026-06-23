package com.hirain.aiagent.infra.geo;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
public class GeoUtils {
    private static final String TAG = "GeoUtils";
    private static final String BASE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private final String apiKey;
    private final OkHttpClient httpClient;
    private final Gson gson;
    public GeoUtils(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
    }
    public interface GeoCallback<T> {
        void onSuccess(T data);
        void onFailure(String error);
    }
    private static class CityCodeResponse {
        String status;
        String info;
        @SerializedName("geocodes")
        List<CityResponse> responses;
    }

    private static class CityResponse {
        String adcode;
    }

    public void getCityCode(String address, GeoCallback<GeoUtils.CityResponse> callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("address", address)
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        httpClient.newCall(request).enqueue(
                new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        callback.onFailure("http request failed: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        if (!response.isSuccessful()) {
                            callback.onFailure("http server error: " + response.code());
                            return;
                        }

                        String json = response.body().string();
                        Log.d("ChatService", "raw response: " + json);
                        CityCodeResponse result = gson.fromJson(json, CityCodeResponse.class);
                        if ("1".equals(result.status) && result.responses !=null && !result.responses.isEmpty()) {
                            CityResponse tmp = result.responses.get(0);
                            callback.onSuccess(tmp);
                        } else {
                            callback.onFailure("api error: " + result.info);
                        }
                    }
                }
        );
    }

    public String getCityCode(String address) {
        final String[] result = {""};
        final CountDownLatch latch = new CountDownLatch(1);
        getCityCode(address, new GeoCallback<CityResponse>() {
            @Override
            public void onSuccess(CityResponse data) {
                result[0] = data.adcode;
                latch.countDown();
            }
            @Override
            public void onFailure(String error) {
                result[0] = "";
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(10, TimeUnit.SECONDS);
            if (!completed) {
                throw new RuntimeException("地理码转换超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("线程被中断", e);
        }
        return result[0];
    }
}
