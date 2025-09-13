package map.web.weatherutils;

import android.content.Context;
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

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import dev.langchain4j.agent.tool.Tool;

public class WeatherUtils {
    private static final String TAG = "WeatherUtils";
    private static final String BASE_URL = "https://restapi.amap.com/v3/weather/weatherInfo";
    private String apiKey;
    private OkHttpClient httpClient;
    private Gson gson;

    public WeatherUtils(Context context, String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
    }

    public void getLiveWeather(String cityCode, WeatherCallback<LiveWeather> callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("city", cityCode)
                .addQueryParameter("extensions", "base")  // 实时天气
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
                        Log.d(TAG, json);
                        LiveWeatherResponse result = gson.fromJson(json, LiveWeatherResponse.class);

                        if ("1".equals(result.status) && result.lives != null && !result.lives.isEmpty()) {
                            callback.onSuccess(result.lives.get(0));
                        } else {
                            callback.onFailure("api error: " + result.info);
                        }
                    }
                }
        );
    }

    public boolean hasTool(String name) {
        return name.equals("getWeather");
    }

    public String handleToolRequest(ToolExecutionRequest request) {
        if (request.name().equals("getWeather")) {
            return getWeather();
        } else {
            return "无效的工具请求。";
        }
    }

    @Tool("可以获取当前天气信息，当用户提到相关天气问题时需要调用")
    public String getWeather() {
        JSONObject json = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);

        getLiveWeather("110000", new WeatherUtils.WeatherCallback<WeatherUtils.LiveWeather>() {
            @Override
            public void onSuccess(WeatherUtils.LiveWeather data) {
                try {
                    json.put("城市", data.city);
                    json.put("天气", data.weather);
                    json.put("温度", data.temperature);
                    json.put("湿度", data.humidity);
                    json.put("风向", data.winddirection + "," + data.windpower + "级");
                    json.put("更新时间", data.reporttime);
                    latch.countDown();
                } catch (JSONException e) {
                    latch.countDown();
                }
            }
            @Override
            public void onFailure(String error) {
                try {
                    json.put("天气查询失败", error);
                    latch.countDown();
                } catch (JSONException e) {
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

    public interface WeatherCallback<T> {
        void onSuccess(T data);
        void onFailure(String error);
    }

    private static class LiveWeatherResponse {
        String status;
        String info;
        @SerializedName("lives")
        List<LiveWeather> lives;
    }

    public static class LiveWeather {
        public String province;      // 省份
        public String city;          // 城市
        public String adcode;        // 区域编码
        public String weather;       // 天气现象
        public String temperature;   // 实时温度
        public String winddirection; // 风向
        public String windpower;     // 风力
        public String humidity;      // 空气湿度
        public String reporttime;    // 发布时间

        @Override
        public String toString() {
            return city + " " + weather + " " + temperature + "℃";
        }

    }
}
