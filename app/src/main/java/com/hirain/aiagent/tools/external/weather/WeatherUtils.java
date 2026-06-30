package com.hirain.aiagent.tools.external.weather;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.json.JSONException;
import org.json.JSONObject;

import dev.langchain4j.agent.tool.P;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import dev.langchain4j.agent.tool.Tool;
import com.hirain.aiagent.infra.geo.*;

public class WeatherUtils {

    private static final String TAG = "WeatherUtils";
    private static final String BASE_URL = "https://restapi.amap.com/v3/weather/weatherInfo";
    private static final int DAY_MIN = 1;
    private static final int DAY_MAX = 4;

    private final String apiKey;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final GeoUtils geoUtils;

    public WeatherUtils(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
        this.geoUtils = new GeoUtils(apiKey);
    }

    // ── 内部异步查询（非工具） ──

    private void getWeatherForecast(String cityCode, int day, WeatherCallback<WeatherForecast> callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("city", cityCode)
                .addQueryParameter("extensions", "all")
                .addQueryParameter("output", "JSON")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
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
                Log.d(TAG, "raw response: " + json);
                WeatherForecastResponse result = gson.fromJson(json, WeatherForecastResponse.class);
                if ("1".equals(result.status) && result.forecasts != null
                        && !result.forecasts.isEmpty()
                        && result.forecasts.get(0).casts != null
                        && result.forecasts.get(0).casts.size() >= DAY_MAX) {
                    WeatherForecast tmp = result.forecasts.get(0).casts.get(day - 1);
                    tmp.city = result.forecasts.get(0).city;
                    tmp.reportTime = result.forecasts.get(0).reportTime;
                    callback.onSuccess(tmp);
                } else {
                    callback.onFailure("api error: " + result.info);
                }
            }
        });
    }

    // ── 工具方法 ──

    /**
     * 查询中国各地区当天及未来三天的天气预报。
     * 当用户询问天气、温度、风力等信息时必须调用此工具。
     */
    @Tool(name = "getWeatherForecast",
          value = "获取中国各地区当天、明天、后天或大后天的天气预报（温度、风向、风力）。"
                + "当用户询问未来天气时必须调用。")
    public String getWeatherForecast(
            @P("中国境内地区名称，如'北京'、'上海'、'深圳市'") String address,
            @P("日期，1=当天、2=明天、3=后天、4=大后天") int day) {
        Log.d(TAG, "getWeatherForecast invoked for: " + address);

        JSONObject json = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);

        String adcode = geoUtils.getCityCode(address);
        if (adcode.isEmpty()) {
            return "天气查询失败：无效的地址信息。";
        }
        Log.d(TAG, "adcode: " + adcode);

        getWeatherForecast(adcode, day, new WeatherCallback<WeatherForecast>() {
            @Override
            public void onSuccess(WeatherForecast data) {
                try {
                    json.put("城市", data.city);
                    json.put("白天天气", data.dayweather);
                    json.put("白天温度", data.daytemp);
                    json.put("白天风向", data.daywind + "," + data.daypower);
                    json.put("夜间天气", data.nightweather);
                    json.put("夜间温度", data.nighttemp);
                    json.put("夜间风向", data.nightwind + "," + data.nightpower);
                    json.put("更新时间", data.reportTime);
                } catch (JSONException e) {
                    // ignore
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(String error) {
                try {
                    json.put("天气查询失败", error);
                } catch (JSONException e) {
                    // ignore
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            boolean completed = latch.await(10, TimeUnit.SECONDS);
            if (!completed) {
                throw new RuntimeException("天气查询超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("线程被中断", e);
        }
        return json.toString();
    }

    // ── 内部类型 ──

    public interface WeatherCallback<T> {
        void onSuccess(T data);
        void onFailure(String error);
    }

    private static class WeatherForecastResponse {
        String status;
        String info;
        @SerializedName("forecasts")
        List<WeatherForecasts> forecasts;
    }

    private static class WeatherForecasts {
        String city;
        @SerializedName("report_time")
        String reportTime;
        @SerializedName("casts")
        List<WeatherForecast> casts;
    }

    public static class WeatherForecast {
        public String city;
        @SerializedName("report_time")
        public String reportTime;
        public String date;
        public String week;
        public String dayweather;
        public String daytemp;
        public String daywind;
        public String daypower;
        public String nightweather;
        public String nighttemp;
        public String nightwind;
        public String nightpower;

        @Override
        public String toString() {
            return city + " " + date + " 星期" + week + " "
                    + dayweather + " " + daytemp + "℃ " + daywind + " " + daypower + "级 "
                    + nightweather + " " + nighttemp + "℃ " + nightwind + " " + nightpower + "级";
        }
    }
}
