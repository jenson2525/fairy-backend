package cn.nuaa.jensonxu.fairy.common.data.llm.agent.request;

import cn.nuaa.jensonxu.fairy.common.data.llm.ChatFileDTO;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.MessageSource;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import lombok.Data;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.UUID;

@Data
public class AgentChatDTO {

    /** 用户 ID */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /** 用户输入的问题 */
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 本次对话携带的附件列表，为空时表示纯文本对话 */
    private List<ChatFileDTO> files;

    /**
     * Agent 会话 ID
     * 为空时由 getter 自动生成，格式：agent_{userId前缀}_{UUID前8位}
     * 客户端首次请求时不传，后续多轮对话传入相同 ID 以维持上下文
     */
    private String sessionId;

    /**
     * 指定本次使用的模型名称
     * 为空时 AgentService 将使用 AgentProperties.defaultModel 兜底
     */
    private String modelName;

    /**
     * 消息来源
     * web/客户端不传即默认 normal；IM 平台显式设置（如 qq）
     * 透传至 RunnableConfig metadata，最终落库到 agent_session_message.source
     */
    private String source = MessageSource.NORMAL;

    /**
     * 最大 ReAct 循环迭代次数
     * 为 null 或 0 时使用全局配置 AgentProperties.maxIterations
     */
    @Min(value = 1, message = "最大迭代次数必须大于0")
    private Integer maxIterations;

    public String getSessionId() {
        if (StringUtils.isBlank(sessionId)) {
            String prefix = StringUtils.isNotBlank(userId) ? userId.substring(0, Math.min(userId.length(), 6)) : "anon";
            this.sessionId = "agent_" + prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        }
        return sessionId;
    }
}
