package cn.nuaa.jensonxu.fairy.integration.agent.memory;

import cn.nuaa.jensonxu.fairy.common.repository.mysql.AgentSessionMessageRepository;
import cn.nuaa.jensonxu.fairy.common.repository.mysql.data.AgentSessionMessageDO;
import cn.nuaa.jensonxu.fairy.common.repository.redis.RedisUtil;
import cn.nuaa.jensonxu.fairy.integration.agent.AgentProperties;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Agent 短期记忆管理
 * 采用先写 MySQL（兜底）再写 Redis（快速访问）的同步双写策略
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentShortTermMemory {

    private static final String KEY_MESSAGES = "agent:memory:short:%s:messages";

    private final RedisUtil redisUtil;
    private final AgentSessionMessageRepository sessionMessageRepository;
    private final AgentProperties agentProperties;

    public void saveMessages(String sessionId, String userId, String source, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 先写 MySQL
        List<AgentSessionMessageDO> doList = buildDOList(sessionId, userId, source, messages);
        sessionMessageRepository.batchInsert(doList);

        // 再写 Redis
        try {
            String key = buildKey(sessionId);
            int ttlHours = agentProperties.getMemory().getShortTerm().getTtlHours();
            List<String> serialized = messages.stream().map(this::serialize).toList();
            redisUtil.listRightPushAll(key, serialized.toArray(new Object[0]));
            redisUtil.expire(key, ttlHours, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[agent] Redis 写入失败，下次请求将从 MySQL 恢复, sessionId: {}", sessionId, e);
        }
    }

    /**
     * 用压缩后的消息列表替换 Redis 中的原始消息列表
     * 供 AgentSummarizationHook 在 BEFORE_MODEL 阶段同步压缩结果使用
     *
     * @param sessionId 会话 ID
     * @param messages  压缩后的消息列表（summaryMsg + 近期 N 条）
     */
    public void replaceMessages(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            String key = buildKey(sessionId);
            int ttlHours = agentProperties.getMemory().getShortTerm().getTtlHours();
            List<String> serialized = messages.stream().map(this::serialize).toList();

            // DEL → RPUSH → EXPIRE，原子性替换整个 List
            // todo: 替换成 lua 脚本
            redisUtil.delete(key);
            redisUtil.listRightPushAll(key, serialized.toArray(new Object[0]));
            redisUtil.expire(key, ttlHours, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[agent] Redis 压缩消息替换失败, sessionId: {}", sessionId, e);
        }
    }


    /**
     * 读取会话消息列表
     * 优先读 Redis，未命中时 fallback 读 MySQL 并回写
     *
     * @param sessionId 会话 ID
     * @return 消息列表，按时间升序
     */
    public List<Message> loadMessages(String sessionId) {
        String key = buildKey(sessionId);
        List<Object> cached = redisUtil.listRange(key, 0, -1);

        if (cached != null && !cached.isEmpty()) {
            return cached.stream().map(o -> deserialize(o.toString())).toList();
        }

        log.info("[agent] Redis 未命中，从 MySQL 回填短期记忆, sessionId: {}", sessionId);  // Redis 未命中，fallback 读 MySQL
        List<AgentSessionMessageDO> doList = sessionMessageRepository.findBySessionId(sessionId);
        if (doList.isEmpty()) {
            return List.of();
        }

        // 回写 Redis
        List<Message> messages = doList.stream().map(d -> deserialize(d.getContent())).toList();
        try {
            int maxMessages = agentProperties.getMemory().getShortTerm().getMaxMessages();
            int ttlHours = agentProperties.getMemory().getShortTerm().getTtlHours();
            List<Message> toWrite = messages.size() > maxMessages ? messages.subList(messages.size() - maxMessages, messages.size()) : messages;
            List<String> serialized = toWrite.stream().map(this::serialize).toList();
            redisUtil.listRightPushAll(key, serialized.toArray(new Object[0]));
            redisUtil.expire(key, ttlHours, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[agent] Redis 回写失败, sessionId: {}", sessionId, e);
        }

        return messages;
    }

    private List<AgentSessionMessageDO> buildDOList(String sessionId, String userId, String source, List<Message> messages) {
        int baseSeq = sessionMessageRepository.countBySessionId(sessionId);
        List<AgentSessionMessageDO> doList = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            doList.add(AgentSessionMessageDO.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .source(source)
                    .role(resolveRole(msg))
                    .content(serialize(msg))
                    .seq(baseSeq + i + 1)
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        return doList;
    }

    private String serialize(Message message) {
        JSONObject json = new JSONObject();
        json.put("role", resolveRole(message));
        json.put("content", message.getText());

        // deepseek 如果执行了工具调用之后不回传 reasoning_content 会触发 400 报错，这里做针对性处理
        if(message instanceof AssistantMessage assistantMessage) {
            Object reasoningContent = assistantMessage.getMetadata().get("reasoningContent");
            if(reasoningContent != null && !reasoningContent.toString().isBlank()) {
                json.put("reasoningContent", reasoningContent.toString());
            }
        }
        return json.toJSONString();
    }

    private Message deserialize(String json) {
        JSONObject obj = JSON.parseObject(json);
        String role = obj.getString("role");
        String content = obj.getString("content");

        if ("assistant".equals(role)) {
            String reasoningContent = obj.getString("reasoningContent");
            if (StringUtils.isNotBlank(reasoningContent)) {
                return AssistantMessage.builder()
                        .content(content)
                        .properties(Map.of("reasoningContent", reasoningContent))
                        .build();
            }
            return new AssistantMessage(content);
        }
        return new UserMessage(content);
    }

    private String resolveRole(Message message) {
        if(message.getMessageType().equals(MessageType.ASSISTANT)) {
            return "assistant";
        } else {
            return "user";
        }
    }

    private String buildKey(String sessionId) {
        return String.format(KEY_MESSAGES, sessionId);
    }
}