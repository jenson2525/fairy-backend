package cn.nuaa.jensonxu.fairy.integration.service.tools.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 和风天气 API 配置属性
 * 绑定 application.yml 中 tools.qweather 前缀下的配置项，
 * 包含 JWT 签发所需的密钥信息与 API 基础地址。
 */
@Data
@Component
@ConfigurationProperties(prefix = "tools.qweather")
public class QWeatherProperties {

    /** JWT Header 中的 kid 字段，在和风天气控制台创建凭据时生成 */
    private String keyId;

    /** JWT Payload 中的 sub 字段，对应和风天气控制台的 ProjectID */
    private String projectId;

    /** Ed25519 私钥，PKCS#8 PEM 格式（含 BEGIN/END 头尾行） */
    private String privateKey;

    /** 开发者专属 API Host */
    private String apiHost;

    public String getApiHost() {
        return apiHost != null ? apiHost.stripTrailing().replaceAll("/+$", "") : "";
    }
}