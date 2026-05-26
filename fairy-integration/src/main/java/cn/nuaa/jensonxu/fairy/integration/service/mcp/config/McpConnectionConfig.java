package cn.nuaa.jensonxu.fairy.integration.service.mcp.config;

import lombok.Data;

import java.util.Map;

/**
 * 第三方 MCP 连接的配置项，支持 SSE 和 Streamable HTTP 两种传输协议
 */
@Data
public class McpConnectionConfig {

    /**
     * 连接名称（对应 Nacos 配置中的 key）
     */
    private String name;

    /**
     * 传输协议类型，支持 "sse"（默认）和 "streamable-http"
     */
    private String type = "sse";

    /**
     * 服务端点 URL
     * SSE 模式下为完整的 SSE 端点地址（如 https://example.com/sse）
     * Streamable HTTP 模式下为完整的 HTTP 端点地址（如 https://example.com/mcp）
     */
    private String url;

    /**
     * 自定义 HTTP 请求头（如 Authorization: Bearer xxx）
     */
    private Map<String, String> headers;

    /**
     * 是否启用，false 时不建立连接
     */
    private boolean enabled = true;
}