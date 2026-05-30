package cn.nuaa.jensonxu.fairy.integration.service.tools.service.impl;

import cn.nuaa.jensonxu.fairy.integration.agent.harness.risk.ToolRisk;
import cn.nuaa.jensonxu.fairy.integration.agent.harness.risk.ToolRiskLevel;
import cn.nuaa.jensonxu.fairy.integration.service.tools.config.QWeatherProperties;
import cn.nuaa.jensonxu.fairy.integration.service.tools.service.McpToolService;
import cn.nuaa.jensonxu.fairy.integration.service.tools.utils.QWeatherJwtProvider;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import lombok.extern.slf4j.Slf4j;

import okhttp3.*;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 天气查询工具
 * 基于和风天气 API，支持全球城市的实时天气查询与未来 3 天预报。
 * 通过 {@link QWeatherJwtProvider} 获取 JWT 完成身份认证，
 * 先调用 Geo API 将城市名解析为 Location ID，再请求对应的天气接口。
 */
@Slf4j
@Service
@ToolRisk(ToolRiskLevel.READ_ONLY)
public class WeatherService implements McpToolService {

    private final QWeatherProperties properties;
    private final QWeatherJwtProvider jwtProvider;
    private final OkHttpClient httpClient;

    public WeatherService(QWeatherProperties properties, QWeatherJwtProvider jwtProvider) {
        this.properties = properties;
        this.jwtProvider = jwtProvider;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
                .build();
    }

    /**
     * 查询指定城市的实时天气
     *
     * @param city 城市名，支持中文或英文
     * @return 格式化的实时天气文本；查询失败时返回错误提示
     */
    @Tool(description = """
            Get the current real-time weather for a specified city.
            Returns temperature, feels-like temperature, weather condition, wind, humidity,
            precipitation, and visibility.
            City name can be in Chinese or English, e.g. '北京', 'Shanghai', '纽约'.
            """)
    public String getCurrentWeather(
            @ToolParam(description = "City name in Chinese or English, e.g. '北京', 'London'") String city) {
        log.info("[weather] 查询实时天气: {}", city);
        try {
            String locationId = lookupLocationId(city);
            String url = properties.getApiHost() + "/v7/weather/now?location=" + locationId + "&lang=zh";
            String body = executeGet(url);

            JSONObject json = JSON.parseObject(body);
            if (!"200".equals(json.getString("code"))) {
                return "天气数据获取失败，code=" + json.getString("code");
            }

            JSONObject now = json.getJSONObject("now");
            return String.format("""
                    %s 当前天气
                    气温：%s°C（体感 %s°C）
                    天气：%s
                    风向/风力：%s %s级（%s km/h）
                    湿度：%s%%
                    降水量：%s mm
                    能见度：%s km
                    气压：%s hPa
                    """,
                    city,
                    now.getString("temp"),
                    now.getString("feelsLike"),
                    now.getString("text"),
                    now.getString("windDir"),
                    now.getString("windScale"),
                    now.getString("windSpeed"),
                    now.getString("humidity"),
                    now.getString("precip"),
                    now.getString("vis"),
                    now.getString("pressure"));
        } catch (Exception e) {
            log.warn("[weather] 实时天气查询失败: {}", e.getMessage());
            return "天气查询失败：" + e.getMessage();
        }
    }

    /**
     * 查询指定城市未来 1~3 天的天气预报
     * @param city 城市名，支持中文或英文
     * @param days 预报天数，有效范围 1~3
     * @return 格式化的逐日预报文本；查询失败时返回错误提示
     */
    @Tool(description = """
            Get the weather forecast for a specified city for the next 1 to 3 days.
            Returns daily max/min temperature, daytime and nighttime weather conditions,
            wind direction, wind scale, humidity, and precipitation for each day.
            City name can be in Chinese or English.
            Note: Only 1 to 3 days forecast is supported.
            """)
    public String getWeatherForecast(
            @ToolParam(description = "City name in Chinese or English, e.g. '上海', 'Tokyo'") String city,
            @ToolParam(description = "Number of forecast days, between 1 and 3") int days) {
        log.info("[weather] 查询 {} 天天气预报: {}", days, city);
        int clampedDays = Math.max(1, Math.min(days, 3));
        try {
            String locationId = lookupLocationId(city);
            String url = properties.getApiHost() + "/v7/weather/3d?location=" + locationId + "&lang=zh";
            String body = executeGet(url);

            JSONObject json = JSON.parseObject(body);
            if (!"200".equals(json.getString("code"))) {
                return "天气预报数据获取失败，code=" + json.getString("code");
            }

            JSONArray daily = json.getJSONArray("daily");
            StringBuilder sb = new StringBuilder(city + " 未来 " + clampedDays + " 天天气预报\n");
            for (int i = 0; i < clampedDays && i < daily.size(); i++) {
                JSONObject d = daily.getJSONObject(i);
                sb.append(String.format(
                        "%s  白天：%s / 夜间：%s  |  气温：%s~%s°C  |  %s %s级  |  湿度：%s%%  |  降水：%s mm\n",
                        d.getString("fxDate"),
                        d.getString("textDay"),    d.getString("textNight"),
                        d.getString("tempMin"),    d.getString("tempMax"),
                        d.getString("windDirDay"), d.getString("windScaleDay"),
                        d.getString("humidity"),
                        d.getString("precip")));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[weather] 天气预报查询失败: {}", e.getMessage());
            return "天气预报查询失败：" + e.getMessage();
        }
    }

    /**
     * 将城市名解析为和风天气 Location ID
     * 调用 Geo API 查询，取相关度最高的第一条结果。
     * @param city 城市名
     * @return Location ID 字符串
     * @throws Exception 城市不存在或 API 返回异常时抛出
     */
    private String lookupLocationId(String city) throws Exception {
        String url = properties.getApiHost() + "/geo/v2/city/lookup?location=" + URLEncoder.encode(city, StandardCharsets.UTF_8) + "&lang=zh";
        String body = executeGet(url);
        JSONObject json = JSON.parseObject(body);

        if (!"200".equals(json.getString("code"))) {
            throw new IllegalArgumentException("城市查询失败，code=" + json.getString("code") + "，请检查城市名称是否正确");
        }

        JSONArray locations = json.getJSONArray("location");
        if (locations == null || locations.isEmpty()) {
            throw new IllegalArgumentException("未找到城市：" + city);
        }

        JSONObject first = locations.getJSONObject(0);
        log.info("[weather] 城市解析: {} → id={}, name={}, adm1={}", city, first.getString("id"), first.getString("name"), first.getString("adm1"));
        return first.getString("id");
    }

    /**
     * 发起携带 JWT 认证的 GET 请求并返回响应体字符串
     * @param url 请求 URL
     * @return 响应体字符串
     * @throws Exception 网络异常或 HTTP 非 2xx 时抛出
     */
    private String executeGet(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + jwtProvider.getToken())
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP 请求失败，状态码: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("响应体为空");
            return body.string();
        }
    }
}