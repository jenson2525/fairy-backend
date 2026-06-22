package cn.nuaa.jensonxu.fairy.common.repository.mysql.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 短期记忆兜底表实体
 * 对应表：agent_session_message
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_session_message")
public class AgentSessionMessageDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 会话 ID（agentSessionId） */
    @TableField("session_id")
    private String sessionId;

    /** 用户 ID */
    @TableField("user_id")
    private String userId;

    /** 消息角色：human / assistant / tool */
    @TableField("role")
    private String role;

    /** 消息内容（JSON 序列化） */
    @TableField("content")
    private String content;

    /** 消息来源：normal（web/客户端）/ qq 等 IM 平台 */
    @TableField("source")
    private String source;

    /** 消息在会话内的顺序号，从 1 起递增 */
    @TableField("seq")
    private Integer seq;

    @TableField("created_at")
    private LocalDateTime createdAt;
}