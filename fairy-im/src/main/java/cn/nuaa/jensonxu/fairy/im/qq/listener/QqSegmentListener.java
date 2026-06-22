package cn.nuaa.jensonxu.fairy.im.qq.listener;

import cn.nuaa.jensonxu.fairy.im.qq.client.NapCatApiClient;
import cn.nuaa.jensonxu.fairy.integration.agent.handler.AgentSegmentListener;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

/**
 * QQ 分段输出监听器
 * 将 Agent 在 ReAct 段边界产出的叙述、工具提示、正文逐段发为 QQ 私聊消息，
 * 段与段之间保持最小间隔防风控。按会话实例化，非单例
 */
@Slf4j
public class QqSegmentListener implements AgentSegmentListener {

    private final NapCatApiClient napCatApiClient;
    private final Long qqNumber;
    private final long sendIntervalMs;

    private boolean firstSend = true;  // 首段立即发，后续段发送前先间隔

    public QqSegmentListener(NapCatApiClient napCatApiClient, Long qqNumber, long sendIntervalMs) {
        this.napCatApiClient = napCatApiClient;
        this.qqNumber = qqNumber;
        this.sendIntervalMs = sendIntervalMs;
    }

    /**
     * 模型一段自然语言（叙述或正文），直接发送
     */
    @Override
    public void onAssistantSegment(String text) {
        sendWithSpacing(text);
    }

    /**
     * 模型未叙述就调用工具时的兜底提示
     */
    @Override
    public void onToolNotice(String toolName) {
        sendWithSpacing("🔧 正在调用 " + toolName + " 工具…");
    }

    /**
     * 流正常结束，仅记录日志
     */
    @Override
    public void onComplete() {
        log.info("[im-qq] 分段回复完成 - qq Number: {}", qqNumber);
    }

    /**
     * 发生错误，向用户发友好提示，技术细节仅记日志
     */
    @Override
    public void onError(String message) {
        log.error("[im-qq] 分段回复异常 - qq Number: {}, error: {}", qqNumber, message);
        sendWithSpacing("⚠️ 抱歉，处理你的消息时出错了，请稍后再试。");
    }

    /**
     * 统一发送：非首段先按间隔等待，再调用客户端发送
     * @param text 要发送的文本
     */
    private void sendWithSpacing(String text) {
        if (StringUtils.isBlank(text)) {
            return;
        }
        if (!firstSend) {
            try {
                Thread.sleep(sendIntervalMs);  // 段间间隔，防 QQ 风控
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // 恢复中断标志
                log.warn("[im-qq] 段间等待被中断 - qq Number: {}", qqNumber);
                return;
            }
        }
        napCatApiClient.sendPrivateText(qqNumber, text);
        firstSend = false;
    }
}