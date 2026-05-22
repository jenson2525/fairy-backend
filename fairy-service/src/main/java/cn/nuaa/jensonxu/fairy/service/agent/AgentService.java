package cn.nuaa.jensonxu.fairy.service.agent;

import cn.nuaa.jensonxu.fairy.common.data.llm.ChatFileDTO;
import cn.nuaa.jensonxu.fairy.common.data.llm.agent.request.AgentChatDTO;
import cn.nuaa.jensonxu.fairy.common.repository.mysql.AgentSessionMetadataRepository;
import cn.nuaa.jensonxu.fairy.common.repository.mysql.data.AgentSessionMetadataDO;
import cn.nuaa.jensonxu.fairy.integration.agent.AgentClientBuilder;
import cn.nuaa.jensonxu.fairy.integration.agent.AgentProperties;
import cn.nuaa.jensonxu.fairy.integration.agent.AgentSessionTitleGenerator;
import cn.nuaa.jensonxu.fairy.integration.agent.handler.AgentConcurrencyLimiter;
import cn.nuaa.jensonxu.fairy.integration.agent.handler.AgentHandler;

import cn.nuaa.jensonxu.fairy.integration.agent.memory.AgentLoadedContext;
import cn.nuaa.jensonxu.fairy.integration.agent.memory.AgentMemoryManager;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentClientBuilder agentClientBuilder;
    private final AgentProperties agentProperties;
    private final AgentMemoryManager agentMemoryManager;
    private final AgentConcurrencyLimiter concurrencyLimiter;
    private final AgentSessionMetadataRepository metadataRepository;
    private final AgentSessionTitleGenerator titleGenerator;
    /**
     * Agent 流式对话入口
     * 创建 SseEmitter 后立即返回，实际执行由 AgentHandler 异步驱动
     * @param agentChatDTO 请求参数（含用户消息、模型名称、agentSessionId 等）
     * @return SseEmitter，HTTP 层持有此对象向客户端推送 Agent 执行事件
     */
    public SseEmitter chat(AgentChatDTO agentChatDTO) {

        String agentSessionId = agentChatDTO.getSessionId();  // 确保 agentSessionId 已生成
        int maxIterations = resolveMaxIterations(agentChatDTO.getMaxIterations());  // 解析最终使用的 maxIterations
        log.info("[agent] 开始 Agent 对话 - userId: {}, agentSessionId: {}, model: {}, maxIterations: {}", agentChatDTO.getUserId(), agentSessionId, agentChatDTO.getModelName(), maxIterations);
        handleSessionMetadata(agentChatDTO, agentSessionId);  // 维护会话元数据，创建标题
        AgentLoadedContext context = agentMemoryManager.loadContext(agentSessionId, agentChatDTO.getUserId());  // ① 加载记忆上下文：短期消息历史 + 长期记忆 System Prompt 前缀
        agentChatDTO.setMessage(buildMessage(agentChatDTO));
        ReactAgent reactAgent = agentClientBuilder.build(agentChatDTO.getModelName(), agentSessionId, agentChatDTO.getUserId(), context);  // ② 构建 ReactAgent：注入 MemorySaver、回填历史、设置 System Prompt
        SseEmitter sseEmitter = new SseEmitter(0L);  // ③ 创建 SSE 连接（0L 表示不超时，由 Agent 执行完毕后主动关闭）
        setSseCallbacks(sseEmitter, agentSessionId);
        AgentHandler agentHandler = new AgentHandler(reactAgent, sseEmitter, agentChatDTO, agentProperties, concurrencyLimiter);  // ④ 实例化 AgentHandler，异步执行 Agent 推理循环
        agentHandler.runV2();

        return sseEmitter;
    }

    /**
     * 维护 agent_session_metadata 表：
     * - 首次会话：插入记录并异步生成标题
     * - 后续会话：更新最后活跃时间
     */
    private void handleSessionMetadata(AgentChatDTO agentChatDTO, String agentSessionId) {
        try {
            if(!metadataRepository.existsBySessionId(agentSessionId)) {
                LocalDateTime now = LocalDateTime.now();
                AgentSessionMetadataDO metadata = AgentSessionMetadataDO.builder()
                        .sessionId(agentSessionId)
                        .userId(agentChatDTO.getUserId())
                        .modelName(agentChatDTO.getModelName())
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                metadataRepository.insert(metadata);
                titleGenerator.generateAndSave(agentSessionId, agentChatDTO.getMessage());  // 异步创建标题
                log.info("[agent] 新会话已创建 - agentSessionId: {}", agentSessionId);
            } else  {
                metadataRepository.updateActiveTime(agentSessionId);
            }
        } catch (Exception e) {
            log.error("[agent] 会话元数据维护失败 - agentSessionId: {}", agentSessionId, e);  // metadata 写入失败不影响对话主流程
        }
    }

    /**
     * 解析最终使用的 maxIterations
     * 取请求值与全局上限的较小值，防止客户端传入过大的值
     */
    private int resolveMaxIterations(Integer requested) {
        int globalMax = agentProperties.getMaxIterations();
        if (requested == null || requested <= 0) {
            return globalMax;
        }
        return Math.min(requested, globalMax);
    }

    /**
     * 设置 SSE 生命周期回调，与 ChatService 保持一致的日志风格
     */
    private void setSseCallbacks(SseEmitter emitter, String agentSessionId) {
        emitter.onCompletion(() ->
                log.info("[agent] SSE 连接完成 - agentSessionId: {}", agentSessionId));

        emitter.onTimeout(() -> {
            log.warn("[agent] SSE 连接超时 - agentSessionId: {}", agentSessionId);
            emitter.complete();
        });

        emitter.onError(e ->
                log.error("[agent] SSE 连接错误 - agentSessionId: {}, 错误: {}", agentSessionId, e.getMessage()));
    }

    /**
     * 构建最终传入 Agent 的用户消息
     * 若请求携带附件，将文件元数据追加到消息末尾，引导 Agent 调用 readUploadedFile 工具
     */
    private String buildMessage(AgentChatDTO agentChatDTO) {
        if (CollectionUtils.isEmpty(agentChatDTO.getFiles())) {
            return agentChatDTO.getMessage();
        }
        StringBuilder sb = new StringBuilder(agentChatDTO.getMessage());
        sb.append("\n\n[附件信息]");
        for (ChatFileDTO file : agentChatDTO.getFiles()) {
            sb.append("\n- 文件名: ").append(file.getFileName())
                    .append(", 路径: ").append(file.getFileId());
        }
        sb.append("\n请使用 readUploadedFile 工具读取上述文件内容后再回答。");
        return sb.toString();
    }
}