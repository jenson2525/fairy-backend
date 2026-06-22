package cn.nuaa.jensonxu.fairy.im.qq.client;

import cn.nuaa.jensonxu.fairy.im.qq.config.NapCatProperties;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NapCat 发消息客户端
 * 封装 OneBot11 的 send_private_msg 接口，对外提供「给指定 QQ 发文本」能力，
 * 内部处理鉴权、长消息分段与段间间隔
 */
@Slf4j
@Component
public class NapCatApiClient {

    private final NapCatProperties napCatProperties;
    private final RestClient restClient;

    /**
     * 基于配置的 apiBaseUrl 构建 RestClient
     */
    public NapCatApiClient(NapCatProperties napCatProperties) {
        this.napCatProperties = napCatProperties;
        this.restClient = RestClient.builder()
                .baseUrl(napCatProperties.getApiBaseUrl())
                .build();
    }

    /**
     * 给指定 QQ 用户发送一段文本
     * 文本过长时按 maxMessageLength 分段，逐段发送并保持段间间隔
     * @param userId 接收方 QQ 号（OneBot 的 user_id）
     * @param text   要发送的文本内容
     */
    public void sendPrivateText(Long userId, String text) {
        if (StringUtils.isBlank(text)) {
            return;  // 不发送空消息
        }

        List<String> segments = splitText(text, napCatProperties.getMaxMessageLength());
        for (int i = 0; i < segments.size(); i++) {
            sendOne(userId, segments.get(i));
            // 段与段之间保持最小间隔防风控，最后一段后无需等待
            if (i < segments.size() - 1) {
                try {
                    Thread.sleep(napCatProperties.getSendIntervalMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();  // 恢复中断标志
                    log.warn("[im-qq] 分段发送被中断 - userId: {}", userId);
                    return;
                }
            }
        }
    }

    /**
     * 实际发起一次 send_private_msg 调用
     * @param userId  接收方 QQ 号
     * @param segment 单段文本
     */
    private void sendOne(Long userId, String segment) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("user_id", userId);
            payload.put("message", segment);

            String response = restClient.post()
                    .uri("/send_private_msg")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + napCatProperties.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            log.info("[im-qq] 发送私聊消息 - userId: {}, 长度: {}, 响应: {}", userId, segment.length(), response);
        } catch (Exception e) {
            log.error("[im-qq] 发送私聊消息失败 - userId: {}", userId, e);  // 单条失败不抛出，避免中断整轮回发
        }
    }

    /**
     * 按最大长度把文本切分为若干段
     * @param text      原始文本
     * @param maxLength 单段最大字符数
     * @return 分段列表，短文本返回单元素列表
     */
    private List<String> splitText(String text, int maxLength) {
        List<String> segments = new ArrayList<>();
        int length = text.length();
        for (int start = 0; start < length; start += maxLength) {
            segments.add(text.substring(start, Math.min(start + maxLength, length)));
        }
        return segments;
    }
}