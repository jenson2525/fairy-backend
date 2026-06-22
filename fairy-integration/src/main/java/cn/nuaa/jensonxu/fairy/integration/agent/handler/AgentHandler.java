package cn.nuaa.jensonxu.fairy.integration.agent.handler;

import cn.nuaa.jensonxu.fairy.common.data.llm.agent.request.AgentChatDTO;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.request.AgentEventDTO;
import cn.nuaa.jensonxu.fairy.integration.agent.AgentProperties;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.AgentSseEventType;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;

import com.alibaba.fastjson2.JSON;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 执行处理器
 * 负责驱动 ReactAgent 流式执行，将各阶段结果映射为 SSE 事件推送给客户端
 * 推理结束后负责触发短期记忆写入和会话结束回调
 */
@Slf4j
@RequiredArgsConstructor
public class AgentHandler {

    private final ReactAgent reactAgent;
    private final SseEmitter sseEmitter;
    private final AgentChatDTO agentChatDTO;
    private final AgentProperties agentProperties;
    private final AgentConcurrencyLimiter concurrencyLimiter;

    private Integer chunkId = 1;  // SSE 数据块序号
    private final StringBuilder assistantTextBuffer = new StringBuilder();  // 累积本轮 Assistant 回复文本

    /**
     * 异步执行入口，由 AgentService 调用后立即返回
     */
    public void runV1() {
        CompletableFuture.runAsync(() -> {
            try {
                sendStart();
                executeAgentStream();
            } catch (Exception e) {
                handleError(e);
            }
        });
    }

    /**
     * 异步执行入口，由 AgentService 调用后立即返回
     * 使用虚拟线程执行，在线程内部竞争 Semaphore 许可：
     *   - 获取成功 → 正常执行 Agent 推理，finally 中释放许可
     *   - 获取超时 → 向客户端推送 429 限流事件，关闭 SSE 连接
     */
    public void runV2() {
        Thread.ofVirtual()
                .name("agent-vt-", 0)
                .start(() -> {
                    boolean acquired = false;
                    try {
                        concurrencyLimiter.acquire();
                        acquired = true;
                        sendStart();
                        executeAgentStream();
                    } catch (AgentConcurrencyException e) {
                        log.warn("[agent] 并发限流触发 - agentSessionId: {}, 等待队列长度: {}", agentChatDTO.getSessionId(), concurrencyLimiter.getAvailablePermits());
                        handleConcurrencyRejected(e);  // 等待超时，向客户端推送限流事件
                    } catch (Exception e) {
                        handleError(e);
                    } finally {
                        if (acquired) {
                            concurrencyLimiter.release();
                        }
                    }
                });
    }

    /**
     * 订阅 ReactAgent 流式输出，阻塞至流结束后写入记忆并发送结束事件
     */
    private void executeAgentStream() {
        try {
            // 传入 threadId，激活 MemorySaver 的跨轮会话隔离
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(agentChatDTO.getSessionId())
                    .addMetadata("user_id", agentChatDTO.getUserId())
                    .addMetadata("source", agentChatDTO.getSource())
                    .build();

            reactAgent.stream(agentChatDTO.getMessage(), runnableConfig)
                    .doOnNext(output -> {
                        try {
                            handleNodeOutput(output);
                        } catch (Exception e) {
                            log.error("[agent] 处理节点输出异常 - agentSessionId: {}",
                                    agentChatDTO.getSessionId(), e);
                        }
                    })
                    .blockLast();

            // 流正常结束，持久化本轮对话消息
            // saveRoundMessages();
            log.info("[agent] 本轮完整回复 - agentSessionId: {}\n{}", agentChatDTO.getSessionId(), assistantTextBuffer.toString());
            sendEnd();

        } catch (Exception e) {
            handleError(e);
        }
    }

    /**
     * 按 OutputType 将 NodeOutput 映射到对应的 SSE 事件
     * 同时累积 Assistant 回复文本到 assistantTextBuffer
     */
    private void handleNodeOutput(NodeOutput output) throws Exception {
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
                        if (agentProperties.isStreamThinking()) {
                            AgentEventDTO event = AgentEventDTO.ofContent(AgentSseEventType.AGENT_THINKING, reasoningContent.toString(), agentChatDTO.getSessionId());
                            sendSseEvent(AgentSseEventType.AGENT_THINKING, JSON.toJSONString(event));
                        }
                    } else {
                        String text = assistantMessage.getText();
                        if (StringUtils.isNotBlank(text)) {
                            assistantTextBuffer.append(text);  // 累积 Assistant 回复，推理结束后一并写入记忆
                            AgentEventDTO event = AgentEventDTO.ofContent(AgentSseEventType.AGENT_ANSWER, text, agentChatDTO.getSessionId());
                            sendSseEvent(AgentSseEventType.AGENT_ANSWER, JSON.toJSONString(event));
                        }
                    }
                }
            }

            case AGENT_MODEL_FINISHED -> {
                if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                        if ("read_skill".equals(toolCall.name())) {
                            String skillName = extractSkillName(toolCall.arguments());
                            AgentEventDTO event = AgentEventDTO.ofSkillLoad(skillName, agentChatDTO.getSessionId(), chunkId);
                            sendSseEvent(AgentSseEventType.AGENT_SKILL_LOAD, JSON.toJSONString(event));
                        } else {
                            AgentEventDTO event = AgentEventDTO.ofToolCall(toolCall.name(), toolCall.arguments(), agentChatDTO.getSessionId(), chunkId);
                            sendSseEvent(AgentSseEventType.AGENT_TOOL_CALL, JSON.toJSONString(event));
                        }
                    }
                }
            }

            case AGENT_TOOL_FINISHED -> {
                if (message instanceof ToolResponseMessage toolResponse) {
                    for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                        if ("read_skill".equals(response.name())) {
                            continue;  // skill 内容不下发前端
                        }
                        AgentEventDTO event = AgentEventDTO.ofToolResult(response.name(), response.responseData(), agentChatDTO.getSessionId(), chunkId);
                        sendSseEvent(AgentSseEventType.AGENT_TOOL_RESULT, JSON.toJSONString(event));
                    }
                }
            }

            default -> log.debug("[agent] 忽略节点输出类型: {}, node: {}", type, streamingOutput.node());
        }
    }

    private void sendStart() throws Exception {
        AgentEventDTO event = AgentEventDTO.ofContent(AgentSseEventType.AGENT_START, agentChatDTO.getModelName(), agentChatDTO.getSessionId());
        sendSseEvent(AgentSseEventType.AGENT_START, JSON.toJSONString(event));
    }

    private void sendEnd() {
        try {
            AgentEventDTO event = AgentEventDTO.ofContent(AgentSseEventType.AGENT_END, null, agentChatDTO.getSessionId());
            sendSseEvent(AgentSseEventType.AGENT_END, JSON.toJSONString(event));
            sendSseEvent(AgentSseEventType.DONE, AgentSseEventType.DONE);
            sseEmitter.complete();
            // agentMemoryManager.onSessionEnd(agentChatDTO.getSessionId(), agentChatDTO.getUserId());  // 会话结束回调，摘要提炼在此处触发
        } catch (Exception e) {
            log.error("[agent] 发送结束事件异常 - agentSessionId: {}", agentChatDTO.getSessionId(), e);
            sseEmitter.completeWithError(e);
        }
    }

    /**
     * 并发限流触发时，向客户端推送专属的 429 错误事件并关闭连接
     * 与 handleError() 区分，方便客户端按事件类型做不同的 UI 提示
     */
    private void handleConcurrencyRejected(AgentConcurrencyException e) {
        try {
            AgentEventDTO event = AgentEventDTO.ofContent(AgentSseEventType.AGENT_ERROR, "429:" + e.getMessage(), agentChatDTO.getSessionId());
            sendSseEvent(AgentSseEventType.AGENT_ERROR, JSON.toJSONString(event));
        } catch (Exception ex) {
            log.error("[agent] 发送限流事件异常", ex);
        } finally {
            sseEmitter.complete();
        }
    }


    private void handleError(Throwable e) {
        log.error("[agent] 执行异常 - agentSessionId: {}", agentChatDTO.getSessionId(), e);
        try {
            AgentEventDTO event = AgentEventDTO.ofContent(AgentSseEventType.AGENT_ERROR, e.getMessage(), agentChatDTO.getSessionId());
            sendSseEvent(AgentSseEventType.AGENT_ERROR, JSON.toJSONString(event));
        } catch (Exception ex) {
            log.error("[agent] 发送错误事件异常", ex);
        } finally {
            sseEmitter.completeWithError(e);
        }
    }

    private void sendSseEvent(String event, String data) throws Exception {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .id(chunkId.toString())
                .name(event)
                .data(data)
                .reconnectTime(3000L);
        sseEmitter.send(builder.build());
        chunkId++;
    }

    private String extractSkillName(String arguments) {
        try {
            return JSON.parseObject(arguments).getString("skill_name");
        } catch (Exception e) {
            log.warn("[agent] 解析 read_skill 参数失败: {}", arguments);
            return "unknown";
        }
    }
}