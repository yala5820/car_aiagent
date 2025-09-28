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

import dev.langchain4j.agent.tool.P;
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
    private final String apiKey;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public WeatherUtils(Context context, String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
    }

    public void getWeatherForecast(String cityCode, int day, WeatherCallback<WeatherForecast> callback) {
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("city", cityCode)
                .addQueryParameter("extensions", "all")  // 实时天气
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
                        WeatherForecastResponse result = gson.fromJson(json, WeatherForecastResponse.class);
                        if ("1".equals(result.status) && result.forecasts !=null && !result.forecasts.isEmpty() &&
                            result.forecasts.get(0).casts != null && result.forecasts.get(0).casts.size() == 4) {
                            Log.d("ChatService", "forecast size: " + result.forecasts.get(0).casts.size());
                            WeatherForecast tmp = result.forecasts.get(0).casts.get(day - 1);
                            tmp.city = result.forecasts.get(0).city;
                            callback.onSuccess(tmp);
                        } else {
                            callback.onFailure("api error: " + result.info);
                        }
                    }
                }
        );
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
        return name.equals("getCurrentWeather") || name.equals("getWeatherForecast");
    }

    public String handleToolRequest(ToolExecutionRequest request) {
        try {
            JSONObject json = new JSONObject(request.arguments());
            if (request.name().equals("getCurrentWeather")) {
                return getCurrentWeather();
            } else if (request.name().equals("getWeatherForecast")) {
                return getWeatherForecast(json.getInt("arg0"));
            } else {
                return "无效的工具请求。";
            }
        } catch (JSONException e) {
            return "无效的工具请求。";
        }
    }

    @Tool("获取实时天气信息（温度、湿度、风向、风力信息），用户询问当前天气或需要获取实时天气情况时必须调用。")
    public String getCurrentWeather() {
        JSONObject json = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);

        getLiveWeather("120000", new WeatherUtils.WeatherCallback<WeatherUtils.LiveWeather>() {
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

    @Tool("获取当天、明天、后天或者大后天的天气预报信息（温度、风向、风力信息），用户询问未来天气或需要获取未来天气预报时必须调用。")
    public String getWeatherForecast(@P(value = "整数，范围：1-4，分别对应当天天气，明天天气，后天天气，大后天天气。") int day) {
        Log.d("ChatService", "invoked");
        JSONObject json = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);

        getWeatherForecast("120000", day, new WeatherUtils.WeatherCallback<WeatherUtils.WeatherForecast>() {
            @Override
            public void onSuccess(WeatherUtils.WeatherForecast data) {
                Log.d("ChatService", "succeed: " + data.toString());
                try {
                    json.put("城市", data.city);
                    json.put("白天天气", data.dayweather);
                    json.put("白天温度", data.daytemp);
                    json.put("白天风向", data.daywind + "," + data.daypower);
                    json.put("夜间天气", data.nightweather);
                    json.put("夜间温度", data.nighttemp);
                    json.put("夜间风向", data.nightwind+","+data.nightpower);
                    latch.countDown();
                } catch (JSONException e) {
                    latch.countDown();
                }
            }
            @Override
            public void onFailure(String error) {
                try {
                    json.put("天气查询失败", error);
                    Log.d("ChatService", "failed: " + error);
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
        public String city;          // 城市
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
    private static class WeatherForecastResponse {
        String status;
        String info;
        @SerializedName("forecasts")
        List<WeatherForecasts> forecasts;
    }

    private static class WeatherForecasts {
        String city;
        @SerializedName("casts")
        List<WeatherForecast> casts;
    }
    public static class WeatherForecast {
        public String city;
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
            return city + " " + date + " 星期" + week + " " +
                dayweather + " " + daytemp + "℃ " + daywind + " " + daypower + "级 " +
                nightweather + " " + nighttemp + "℃ " + nightwind + " " + nightpower + "级";
        }

    }
}
