package cn.nuaa.jensonxu.fairy.integration.agent.memory.hook;

import cn.nuaa.jensonxu.fairy.common.data.llm.agent.MessageSource;
import cn.nuaa.jensonxu.fairy.common.rocketmq.message.AgentMemoryExtractMessage;
import cn.nuaa.jensonxu.fairy.common.rocketmq.message.AgentMemoryPersistMessage;
import cn.nuaa.jensonxu.fairy.integration.agent.memory.AgentShortTermMemory;
import cn.nuaa.jensonxu.fairy.integration.agent.memory.mq.AgentMemoryExtractProducer;
import cn.nuaa.jensonxu.fairy.integration.agent.memory.mq.AgentMemoryMessageProducer;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 每轮 Agent 推理结束后的记忆处理钩子（AFTER_AGENT）
 * 职责一：提取本轮 UserMessage + AssistantMessage，持久化到短期记忆（MySQL + Redis）。MySQL 写入失败时降级投递 AgentMemoryPersistMessage 进行兜底重试
 * 职责二：MySQL 写入成功后，投递 AgentMemoryExtractMessage 触发长期记忆异步提炼。仅在写入成功时投递，确保消费者读取 MySQL 时数据已就绪
 */
@Slf4j
@Component
@HookPositions({HookPosition.AFTER_AGENT})
public class AfterAgentMemoryHook extends AgentHook {

    private static final String USER_ID_KEY = "user_id";
    private static final String MESSAGES_KEY = "messages";
    private static final String SOURCE_KEY = "source";

    private final AgentShortTermMemory shortTermMemory;
    private final AgentMemoryMessageProducer persistProducer;
    private final AgentMemoryExtractProducer extractProducer;

    public AfterAgentMemoryHook(AgentShortTermMemory shortTermMemory, AgentMemoryMessageProducer persistProducer, AgentMemoryExtractProducer extractProducer) {
        this.shortTermMemory = shortTermMemory;
        this.persistProducer = persistProducer;
        this.extractProducer = extractProducer;
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        String sessionId = config.threadId().orElse(null);
        String userId = config.metadata(USER_ID_KEY).map(Object::toString).orElse(null);
        String source = config.metadata(SOURCE_KEY).map(Object::toString).orElse(MessageSource.NORMAL);

        if (sessionId == null || userId == null) {
            log.warn("[after-agent-memory] 缺少 sessionId 或 userId，跳过记忆写入");
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Message> messages = state.<List<Message>>value(MESSAGES_KEY).orElse(List.of());
        UserMessage lastHuman = null;
        AssistantMessage lastAssistant = null;

        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (lastAssistant == null && msg instanceof AssistantMessage am && !am.hasToolCalls() && StringUtils.isNotBlank(am.getText())) {
                lastAssistant = am;
            }
            if (lastHuman == null && msg instanceof UserMessage) {
                lastHuman = (UserMessage) msg;
            }
            if (lastHuman != null && lastAssistant != null) break;
        }

        if (lastHuman == null || lastAssistant == null) {
            log.debug("[after-agent-memory] 未找到完整消息对，跳过写入, sessionId: {}", sessionId);
            return CompletableFuture.completedFuture(Map.of());
        }

        log.info("[after-agent-memory] 本轮消息提取完成, sessionId: {}", sessionId);
        boolean mysqlSuccess = persistShortTermMemory(sessionId, userId, source, lastHuman, lastAssistant);

        // 仅在 MySQL 写入成功后投递提炼消息，保证消费者读取时数据已就绪
        if (mysqlSuccess) {
            extractProducer.sendExtractMessage(AgentMemoryExtractMessage.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .originTimestamp(System.currentTimeMillis())
                    .build());
            log.info("[after-agent-memory] 长期记忆提炼消息已投递, sessionId: {}", sessionId);
        }

        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public String getName() {
        return "AfterAgentMemoryHook";
    }

    /**
     * 写入短期记忆，失败时降级投递兜底 MQ 消息
     *
     * @return true=MySQL 写入成功；false=写入失败已降级
     */
    private boolean persistShortTermMemory(String sessionId, String userId, String source, UserMessage human, AssistantMessage assistant) {
        try {
            shortTermMemory.saveMessages(sessionId, userId, source, List.of(human, assistant));
            log.info("[after-agent-memory] 短期记忆写入成功, sessionId: {}", sessionId);
            return true;
        } catch (Exception e) {
            log.error("[after-agent-memory] MySQL 写入失败，降级投递兜底 MQ, sessionId: {}", sessionId, e);
            persistProducer.sendPersistMessage(AgentMemoryPersistMessage.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .source(source)
                    .humanContent(human.getText())
                    .assistantContent(assistant.getText())
                    .originTimestamp(System.currentTimeMillis())
                    .build());
            return false;
        }
    }
}