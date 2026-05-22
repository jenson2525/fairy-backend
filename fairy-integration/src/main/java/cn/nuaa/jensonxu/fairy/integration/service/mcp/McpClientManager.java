package cn.nuaa.jensonxu.fairy.integration.service.mcp;

import cn.nuaa.jensonxu.fairy.integration.service.mcp.config.McpConnectionConfig;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 第三方 MCP 客户端动态管理器
 * 监听 Nacos mcp_client_config.yml，动态管理 McpAsyncClient 的生命周期
 * 对外提供 getActiveToolCallbacks()，供 AgentClientBuilder 按需获取当前活跃工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpClientManager {

    private final NacosConfigManager nacosConfigManager;

    private static final String DATA_ID = "mcp_client_config";
    private static final String GROUP   = "FAIRY_MCP_GROUP";

    private final ConcurrentHashMap<String, McpAsyncClient> activeClients = new ConcurrentHashMap<>();  // 活跃的 McpAsyncClient，key 为连接名称
    private volatile ToolCallback[] cachedToolCallbacks = new ToolCallback[0];  // 缓存工具回调，仅在客户端集合变化时刷新，避免每次请求都触发 listTools() 远程调用

    @PostConstruct
    public void init() {
        try {
            ConfigService configService = nacosConfigManager.getConfigService();

            // 1. 启动时加载初始配置
            String content = configService.getConfig(DATA_ID, GROUP, 5000);
            if (StringUtils.isNotBlank(content)) {
                loadConnections(parseConfigs(content));
            } else {
                log.warn("[mcp] 未获取到初始配置，dataId={}, group={}", DATA_ID, GROUP);
            }

            // 2. 注册 Nacos 监听器，感知后续变更
            configService.addListener(DATA_ID, GROUP, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("[mcp] 检测到配置变更，开始热更新...");
                    try {
                        reconcile(parseConfigs(configInfo));  // 监测到配置变更时，进行 mcp 配置热更新
                    } catch (Exception e) {
                        log.error("[mcp] 热更新失败", e);
                    }
                }
            });
            log.info("[mcp] McpClientManager 初始化完成，监听 dataId={}, group={}", DATA_ID, GROUP);

        } catch (Exception e) {
            log.error("[mcp] McpClientManager 初始化异常", e);
        }
    }

    /**
     * 获取当前所有活跃的第三方 MCP 工具
     */
    public ToolCallback[] getActiveToolCallbacks() {
        return cachedToolCallbacks;
    }

    /**
     * 初始化时加载所有启用的连接
     */
    private void loadConnections(Map<String, McpConnectionConfig> configs) {
        configs.forEach((name, config) -> {
            if (config.isEnabled()) {
                createAndRegister(name, config);
            } else {
                log.info("[mcp] 连接 {} 已禁用，跳过", name);
            }
        });
    }

    /**
     * diff 新旧配置，增量热更新：
     * - 新增 enabled=true 且不在 activeClients → 建立连接
     * - enabled=false 且在 activeClients       → 关闭连接
     * - 从配置中完全删除且在 activeClients       → 关闭连接
     */
    private void reconcile(Map<String, McpConnectionConfig> newConfigs) {
        // 新增 / 重新启用
        newConfigs.forEach((name, config) -> {
            boolean active = activeClients.containsKey(name);
            if (config.isEnabled() && !active) {
                createAndRegister(name, config);
            } else if (!config.isEnabled() && active) {
                closeAndRemove(name);
            }
        });

        // 从配置中完全删除的连接
        activeClients.keySet().stream()
                .filter(name -> !newConfigs.containsKey(name))
                .toList()
                .forEach(this::closeAndRemove);
        refreshCache();
    }

    /**
     * 建立 SSE 连接，初始化 McpAsyncClient 并存入 Map
     */
    private void createAndRegister(String name, McpConnectionConfig config) {
        try {
            HttpClientSseClientTransport transport = HttpClientSseClientTransport
                    .builder(config.getUrl())
                    .sseEndpoint(config.getSseEndpoint())
                    .build();

            McpAsyncClient client = McpClient.async(transport)
                    .clientInfo(new McpSchema.Implementation("fairy-mcp-client", "1.0.0"))
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();

            client.initialize().block(Duration.ofSeconds(30));
            activeClients.put(name, client);
            log.info("[mcp] 连接建立成功: {}", name);

        } catch (Exception e) {
            log.error("[mcp] 连接建立失败: {}", name, e);
        }
    }

    /**
     * 关闭 McpAsyncClient 并从 Map 移除
     */
    private void closeAndRemove(String name) {
        McpAsyncClient client = activeClients.remove(name);
        if (client != null) {
            try {
                client.closeGracefully().block(Duration.ofSeconds(10));
                log.info("[mcp] 连接已关闭: {}", name);
            } catch (Exception e) {
                log.warn("[mcp] 连接关闭异常: {}", name, e);
            }
        }
    }

    /**
     * 客户端集合变化后刷新工具回调缓存
     */
    private void refreshCache() {
        if (activeClients.isEmpty()) {
            cachedToolCallbacks = new ToolCallback[0];
            log.info("[mcp] 当前无活跃连接，工具回调缓存已清空");
            return;
        }

        Mono.fromCallable(() -> new AsyncMcpToolCallbackProvider(new ArrayList<>(activeClients.values())).getToolCallbacks())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        callbacks -> {
                            cachedToolCallbacks = callbacks;
                            log.info("[mcp] 工具回调缓存已刷新，共 {} 个第三方工具", callbacks.length);
                        },
                        e -> log.error("[mcp] 工具回调缓存刷新失败", e)
                );
    }

    /**
     * 解析 Nacos YAML 内容，返回 McpConnectionConfig Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, McpConnectionConfig> parseConfigs(String content) {
        Map<String, McpConnectionConfig> result = new LinkedHashMap<>();
        try {
            Map<String, Object> root = new Yaml().load(content);
            Map<String, Object> mcpSection = (Map<String, Object>) root.get("mcp");
            if (mcpSection == null) return result;
            Map<String, Object> connections = (Map<String, Object>) mcpSection.get("connections");
            if (connections == null) return result;

            connections.forEach((name, value) -> {
                Map<String, Object> props = (Map<String, Object>) value;
                McpConnectionConfig cfg = new McpConnectionConfig();
                cfg.setName(name);
                cfg.setUrl((String) props.get("url"));
                cfg.setSseEndpoint((String) props.get("sse-endpoint"));
                Object enabled = props.get("enabled");
                cfg.setEnabled(enabled == null || Boolean.TRUE.equals(enabled));
                result.put(name, cfg);
            });
        } catch (Exception e) {
            log.error("[mcp] YAML 配置解析失败", e);
        }
        return result;
    }

    /**
     * 应用完全启动后（Reactor 调度器就绪）再刷新工具回调缓存
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("[mcp] 应用启动完成，开始刷新工具回调缓存...");
        refreshCache();
    }

    @PreDestroy
    public void destroy() {
        log.info("[mcp] 应用停止，关闭所有 MCP 客户端连接...");
        new ArrayList<>(activeClients.keySet()).forEach(this::closeAndRemove);
    }
}