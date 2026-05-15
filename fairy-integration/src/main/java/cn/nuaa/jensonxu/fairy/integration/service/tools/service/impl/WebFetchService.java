package cn.nuaa.jensonxu.fairy.integration.service.tools.service.impl;

import cn.nuaa.jensonxu.fairy.integration.service.tools.service.McpToolService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/*
 * 网页内容抓取工具
 * 根据提供的 URL，发起 HTTP 请求获取网页原始 HTML，
 * 使用 Jsoup 解析并提取干净的可读正文，去除导航栏、广告、脚本等干扰元素，
 * 截取前 3000 字符后返回给模型，供其摘要或分析。
 * 该工具仅适用于 URL 已知的场景，不作为网络搜索的替代手段。
 * 仅支持 text/html 类型的响应内容。
 */
@Slf4j
@Service
public class WebFetchService implements McpToolService {

    private static final int MAX_CONTENT_LENGTH = 3000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Safari/537.36";

    private final OkHttpClient httpClient;

    public WebFetchService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 抓取指定 URL 的网页正文内容
     * 携带浏览器 User-Agent 发起 GET 请求，校验 HTTP 状态码与 Content-Type 后，
     * 调用 {@link #extractText} 提取正文并截断返回。
     * @param url 目标网页的完整 URL
     * @return 提取后的纯文本正文；若请求失败或内容为空，返回对应的错误提示
     */
    @Tool(description = """
            Fetch and extract the main text content from a specific URL.
            Use this when the user provides a URL directly and wants to read, summarize,
            or analyze the content of that page.
            Do NOT use this as a substitute for web search — use it only when a URL is already known.
            Returns the extracted plain text (up to 3000 characters).
            """)
    public String fetchContent(
            @ToolParam(description = "The full URL to fetch, e.g. 'https://example.com/article'")
            String url) {
        log.info("[web-fetch] 抓取 URL: {}", url);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[web-fetch] 请求失败, HTTP {}: {}", response.code(), url);
                return String.format("Failed to fetch URL. HTTP status: %d", response.code());
            }

            String contentType = response.header("Content-Type", "");
            if (contentType != null && !contentType.contains("text/html")) {
                log.warn("[web-fetch] 非 HTML 内容: {}", contentType);
                return String.format(
                        "The URL returned non-HTML content (Content-Type: %s). " +
                                "This tool only supports HTML pages.", contentType);
            }

            ResponseBody body = response.body();
            if (body == null) {
                return "Failed to fetch URL: response body is empty.";
            }

            String html = body.string();
            String text = extractText(html, url);

            if (text.isBlank()) {
                return "The page was fetched successfully but no readable text content was found.";
            }

            log.info("[web-fetch] 抓取成功, 正文长度: {} 字符, URL: {}", text.length(), url);
            return String.format("Content from %s:\n\n%s", url, text);

        } catch (Exception e) {
            log.warn("[web-fetch] 抓取异常: {}, URL: {}", e.getMessage(), url);
            return "Failed to fetch URL: " + e.getMessage();
        }
    }

    /**
     * 从原始 HTML 中提取干净的可读正文
     * 使用 Jsoup 解析 HTML，移除 script、style、nav、footer、
     * 广告容器等干扰元素后，提取 body 纯文本并规范化空白字符。
     * 若正文超过 {@link #MAX_CONTENT_LENGTH}，截断并附加总长度说明。
     * @param html 网页原始 HTML 字符串
     * @param url  页面来源 URL，用于 Jsoup 解析相对链接
     * @return 处理后的纯文本正文
     */
    private String extractText(String html, String url) {
        Document doc = Jsoup.parse(html, url);

        doc.select("script, style, nav, footer, header, aside, " +
                "iframe, noscript, [class~=(?i)ad], [class~=(?i)banner], " +
                "[class~=(?i)cookie], [class~=(?i)popup]").remove();
        String text = doc.select("body").text();
        text = text.replaceAll("\\s{2,}", " ").trim();

        if (text.length() <= MAX_CONTENT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_CONTENT_LENGTH) +
                String.format("... [Content truncated, total length: %d characters]", text.length());
    }
}