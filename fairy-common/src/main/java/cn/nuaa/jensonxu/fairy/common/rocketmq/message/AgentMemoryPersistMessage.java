package cn.nuaa.jensonxu.fairy.common.rocketmq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Agent 短期记忆持久化 MQ 消息体
 * 在 ShortTermRedisSaveHook 写入 MySQL 失败时投递，由消费者负责重试写入
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMemoryPersistMessage implements Serializable {

    /** 会话 ID */
    private String sessionId;

    /** 用户 ID */
    private String userId;

    /** 本轮用户消息内容 */
    private String humanContent;

    /** 本轮 Assistant 回复内容 */
    private String assistantContent;

    /** 首次投递时间戳，用于日志追踪 */
    private long originTimestamp;

    /** 消息来源：normal / qq 等 */
    private String source;
}