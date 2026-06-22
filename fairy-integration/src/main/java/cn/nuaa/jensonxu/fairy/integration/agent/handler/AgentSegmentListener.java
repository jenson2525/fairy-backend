package cn.nuaa.jensonxu.fairy.integration.agent.handler;

/**
 * Agent 分段输出监听器
 * Agent 在 ReAct 执行的段边界回调本接口，由调用方决定每段如何落地（如包装成 IM 消息发送）
 */
public interface AgentSegmentListener {

    /**
     * 模型产出的一段自然语言
     * 可能是工具调用前的叙述，也可能是最终正文
     * @param text 该段完整文本
     */
    void onAssistantSegment(String text);

    /**
     * 模型未叙述就直接调用工具时的兜底通知
     * 调用方据此合成「正在调用某工具」的提示
     * @param toolName 被调用的工具名
     */
    void onToolNotice(String toolName);

    /**
     * 流正常结束回调
     */
    default void onComplete() {
    }

    /**
     * 发生不可恢复错误时回调
     * @param message 错误信息
     */
    default void onError(String message) {
    }
}