package cn.nuaa.jensonxu.fairy.integration.agent.handler;

import cn.nuaa.jensonxu.fairy.common.data.llm.agent.request.AgentChatDTO;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Agent 分段执行处理器
 * 驱动 ReactAgent 流式执行，在 ReAct 段边界将模型叙述、工具提示、正文逐段回调给 AgentSegmentListener，
 * 供 IM 等非流式场景按段下发消息
 */
@Slf4j
@RequiredArgsConstructor
public class AgentSegmentHandler {

    /** read_skill 工具不向用户暴露技能加载过程 */
    private static final String TOOL_READ_SKILL = "read_skill";

    private final ReactAgent reactAgent;
    private final AgentChatDTO agentChatDTO;
    private final AgentSegmentListener listener;

    private final StringBuilder segmentBuffer = new StringBuilder();  // 累积当前段的模型文本

    /**
     * 阻塞执行整条 Agent 流，结束后回调 onComplete
     */
    public void run() {
        try {
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(agentChatDTO.getSessionId())
                    .addMetadata("user_id", agentChatDTO.getUserId())
                    .addMetadata("source", agentChatDTO.getSource())
                    .build();

            reactAgent.stream(agentChatDTO.getMessage(), runnableConfig)
                    .doOnNext(this::handleNodeOutput)
                    .blockLast();

            flushSegment();  // 发出最后一段正文
            listener.onComplete();
        } catch (Exception e) {
            log.error("[agent-seg] 分段执行异常 - agentSessionId: {}", agentChatDTO.getSessionId(), e);
            listener.onError(e.getMessage());
        }
    }

    /**
     * 按 OutputType 将流事件映射为段回调
     * @param output 流式节点输出
     */
    private void handleNodeOutput(NodeOutput output) {
        if (!(output instanceof StreamingOutput streamingOutput)) {
            return;
        }

        OutputType type = streamingOutput.getOutputType();
        var message = streamingOutput.message();

        switch (type) {
            case AGENT_MODEL_STREAMING -> {
                if (message instanceof AssistantMessage assistantMessage) {
                    Object reasoningContent = assistantMessage.getMetadata().get("reasoningContent");
                    if (reasoningContent != null && StringUtils.isNotBlank(reasoningContent.toString())) {
                        return;  // 跳过推理过程，IM 不下发 thinking
                    }
                    String text = assistantMessage.getText();
                    if (StringUtils.isNotBlank(text)) {
                        segmentBuffer.append(text);  // 累积当前段文本
                    }
                }
            }

            case AGENT_MODEL_FINISHED -> {
                if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                    boolean hadNarration = StringUtils.isNotBlank(segmentBuffer.toString());
                    flushSegment();  // 有叙述则先把叙述作为一段发出
                    if (!hadNarration) {
                        // 模型没叙述就调工具，逐个合成兜底提示（技能加载除外）
                        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                            if (!TOOL_READ_SKILL.equals(toolCall.name())) {
                                listener.onToolNotice(toolCall.name());
                            }
                        }
                    }
                }
            }

            default -> {
                // AGENT_TOOL_FINISHED 等其余类型不处理：工具原始结果不外发
            }
        }
    }

    /**
     * 缓冲非空则作为一段回调 onAssistantSegment，并清空缓冲
     */
    private void flushSegment() {
        if (segmentBuffer.isEmpty()) {
            return;
        }
        String text = segmentBuffer.toString().trim();
        segmentBuffer.setLength(0);
        if (StringUtils.isNotBlank(text)) {
            listener.onAssistantSegment(text);
        }
    }
}