package cn.nuaa.jensonxu.fairy.im.qq.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * NapCat 对接配置属性
 * 绑定 application.yml 中 fairy.im.qq.napcat.* 配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "fairy.im.qq.napcat")
public class NapCatProperties {

    /** NapCat HTTP 服务器地址（fairy-api-server） */
    private String apiBaseUrl = "http://127.0.0.1:3000";

    /** NapCat HTTP 服务器鉴权 token */
    private String accessToken;

    /** 单条 QQ 消息最大字符数，超过则分段发送 */
    private int maxMessageLength = 2000;

    /** 连续发送消息之间的最小间隔（毫秒），防 QQ 风控 */
    private long sendIntervalMs = 300;
}