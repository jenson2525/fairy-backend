package cn.nuaa.jensonxu.fairy.integration.agent.memory.mq;

import cn.nuaa.jensonxu.fairy.common.repository.mysql.AgentSessionMessageRepository;
import cn.nuaa.jensonxu.fairy.common.repository.mysql.data.AgentSessionMessageDO;
import cn.nuaa.jensonxu.fairy.common.rocketmq.AbstractRocketMQConsumer;
import cn.nuaa.jensonxu.fairy.common.rocketmq.message.AgentMemoryPersistMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 短期记忆持久化消息消费者
 * 消费由 AgentMemoryMessageProducer 投递的重试消息，将 Human + Assistant 消息写入 MySQL
 * RocketMQ 消费失败时自动重试（默认最多 16 次，指数退避），超出后进入死信队列
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "agent-memory-persist",
        consumerGroup = "agent-memory-persist-group"
)
public class AgentMemoryMessageConsumer extends AbstractRocketMQConsumer<AgentMemoryPersistMessage> implements RocketMQListener<AgentMemoryPersistMessage> {

    private final AgentSessionMessageRepository sessionMessageRepository;

    @Override
    public void onMessage(AgentMemoryPersistMessage message) {
        consume(message);
    }

    @Override
    protected boolean doConsume(AgentMemoryPersistMessage message) {
        log.info("[memory] 开始重试写入短期记忆, sessionId: {}, originTimestamp: {}", message.getSessionId(), message.getOriginTimestamp());
        int baseSeq = sessionMessageRepository.countBySessionId(message.getSessionId());

        AgentSessionMessageDO humanDO = buildDO(
                message.getSessionId(),
                message.getUserId(),
                message.getSource(),
                "user",
                serializeContent("user", message.getHumanContent()),
                baseSeq + 1
        );
        AgentSessionMessageDO assistantDO = buildDO(
                message.getSessionId(),
                message.getUserId(),
                message.getSource(),
                "assistant",
                serializeContent("assistant", message.getAssistantContent()),
                baseSeq + 2
        );

        sessionMessageRepository.batchInsert(List.of(humanDO, assistantDO));
        log.info("[memory] 短期记忆重试写入成功, sessionId: {}", message.getSessionId());
        return true;
    }

    @Override
    protected void afterConsumeFailed(AgentMemoryPersistMessage message) {
        log.warn("[memory] 本次消费失败，等待 RocketMQ 自动重试, sessionId: {}", message.getSessionId());
    }

    @Override
    protected void onConsumeError(AgentMemoryPersistMessage message, Throwable throwable) {
        log.error("[memory] 消费异常，触发 RocketMQ 重试, sessionId: {}", message.getSessionId(), throwable);  // 抛出异常，触发 RocketMQ 重试机制
    }

    private AgentSessionMessageDO buildDO(String sessionId, String userId, String source, String role, String content, int seq) {
        return AgentSessionMessageDO.builder()
                .sessionId(sessionId)
                .userId(userId)
                .source(source)
                .role(role)
                .content(content)
                .seq(seq)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * 与 AgentShortTermMemory.serialize() 保持一致的序列化格式
     * {"role":"user","content":"..."}
     */
    private String serializeContent(String role, String content) {
        return String.format("{\"role\":\"%s\",\"content\":\"%s\"}", role, content);
    }
}