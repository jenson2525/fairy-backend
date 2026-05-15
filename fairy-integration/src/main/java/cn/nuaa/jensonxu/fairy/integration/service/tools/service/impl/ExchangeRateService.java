package cn.nuaa.jensonxu.fairy.integration.service.tools.service.impl;

import cn.nuaa.jensonxu.fairy.integration.service.tools.service.McpToolService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 汇率查询与货币换算工具
 * 基于 Frankfurter API（数据来源：欧洲中央银行），提供主流货币的
 * 实时汇率查询和金额换算能力。无需 API Key，每个 ECB 工作日更新数据。
 */
@Slf4j
@Service
public class ExchangeRateService implements McpToolService {

    private static final String BASE_URL = "https://api.frankfurter.app";
    private final OkHttpClient httpClient;

    public ExchangeRateService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
                .build();
    }

    /**
     * 查询两种货币之间的最新汇率
     *
     * @param from 源货币 ISO 4217 代码
     * @param to   目标货币 ISO 4217 代码
     * @return 格式化的汇率结果及数据日期
     */
    @Tool(description = """
            Get the latest exchange rate between two currencies.
            Returns the current rate and the data date (updated on ECB business days).
            Use standard ISO 4217 currency codes, e.g. USD, EUR, CNY, JPY, GBP, KRW, HKD.
            """)
    public String getRate(
            @ToolParam(description = "Source currency code, e.g. 'USD'") String from,
            @ToolParam(description = "Target currency code, e.g. 'CNY'") String to) {
        log.info("[exchange-rate] 查询汇率: {} -> {}", from, to);
        try {
            String fromUpper = from.trim().toUpperCase();
            String toUpper = to.trim().toUpperCase();

            if (fromUpper.equals(toUpper)) {
                return String.format("1 %s = 1 %s", fromUpper, toUpper);
            }

            String url = BASE_URL + "/latest?from=" + fromUpper + "&to=" + toUpper;
            String body = executeGet(url);

            JSONObject json = JSON.parseObject(body);
            JSONObject rates = json.getJSONObject("rates");

            if (rates == null || !rates.containsKey(toUpper)) {
                return String.format("Unable to retrieve rate for %s -> %s. " +
                        "Please check that both currency codes are valid ISO 4217 codes.", from, to);
            }

            double rate = rates.getDoubleValue(toUpper);
            String date = json.getString("date");

            log.info("[exchange-rate] 汇率结果: 1 {} = {} {}, 日期: {}", fromUpper, rate, toUpper, date);
            return String.format("1 %s = %.6f %s  (as of %s, source: ECB)", fromUpper, rate, toUpper, date);
        } catch (Exception e) {
            log.warn("[exchange-rate] 汇率查询失败: {}", e.getMessage());
            return "Failed to retrieve exchange rate: " + e.getMessage();
        }
    }

    /**
     * 将指定金额从一种货币换算为另一种货币
     *
     * @param amount 待换算金额
     * @param from   源货币 ISO 4217 代码
     * @param to     目标货币 ISO 4217 代码
     * @return 格式化的换算结果，含汇率和数据日期
     */
    @Tool(description = """
            Convert a monetary amount from one currency to another using the latest exchange rate.
            Returns the converted amount and the exchange rate used.
            Use standard ISO 4217 currency codes, e.g. USD, EUR, CNY, JPY, GBP, KRW, HKD.
            """)
    public String doConvert(
            @ToolParam(description = "Amount to convert, e.g. 100.0") double amount,
            @ToolParam(description = "Source currency code, e.g. 'USD'") String from,
            @ToolParam(description = "Target currency code, e.g. 'CNY'") String to) {
        log.info("[exchange-rate] 货币换算: {} {} -> {}", amount, from, to);
        try {
            String fromUpper = from.trim().toUpperCase();
            String toUpper = to.trim().toUpperCase();

            if (fromUpper.equals(toUpper)) {
                return String.format("%.2f %s = %.2f %s", amount, fromUpper, amount, toUpper);
            }

            String url = BASE_URL + "/latest?amount=" + amount + "&from=" + fromUpper + "&to=" + toUpper;
            String body = executeGet(url);

            JSONObject json = JSON.parseObject(body);
            JSONObject rates = json.getJSONObject("rates");

            if (rates == null || !rates.containsKey(toUpper)) {
                return String.format("Unable to convert %s to %s. " + "Please check that both currency codes are valid ISO 4217 codes.", from, to);
            }

            double result = rates.getDoubleValue(toUpper);
            double rate = result / amount;
            String date = json.getString("date");

            log.info("[exchange-rate] 换算结果: {} {} = {} {}", amount, fromUpper, result, toUpper);
            return String.format("%.2f %s = %.2f %s  (rate: 1 %s = %.6f %s, as of %s, source: ECB)", amount, fromUpper, result, toUpper, fromUpper, rate, toUpper, date);
        } catch (Exception e) {
            log.warn("[exchange-rate] 货币换算失败: {}", e.getMessage());
            return "Failed to convert currency: " + e.getMessage();
        }
    }

    private String executeGet(String url) throws Exception {
        Request request = new Request.Builder().url(url).get().build();
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