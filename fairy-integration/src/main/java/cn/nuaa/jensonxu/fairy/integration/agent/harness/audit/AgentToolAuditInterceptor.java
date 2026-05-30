package cn.nuaa.jensonxu.fairy.integration.agent.harness.audit;

import cn.nuaa.jensonxu.fairy.integration.agent.harness.risk.ToolRiskLevel;
import cn.nuaa.jensonxu.fairy.integration.agent.harness.risk.ToolRiskRegistry;

import com.alibaba.cloud.ai.graph.agent.interceptor.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolAuditInterceptor extends ToolInterceptor {

    private static final int SUMMARY_MAX_LENGTH = 200;

    private final ToolRiskRegistry toolRiskRegistry;

    /**
     * 工具执行拦截入口
     * 在 handler.call() 前后分别执行 PreToolUse 和 PostToolUse 审计逻辑
     */
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        String toolCallId = request.getToolCallId();
        String sessionId = request.getExecutionContext()
                .flatMap(ToolCallExecutionContext::threadId)
                .orElse("unknown");
        ToolRiskLevel riskLevel = toolRiskRegistry.getRiskLevel(toolName);

        logPreToolUse(toolName, toolCallId, sessionId, riskLevel, request.getArguments());
        long startTime = System.currentTimeMillis();
        try {
            ToolCallResponse response = handler.call(request);
            long duration = System.currentTimeMillis() - startTime;
            logPostToolUse(toolName, toolCallId, sessionId, riskLevel, duration, response);
            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[tool-audit] 工具执行异常 | tool: {}, risk: {}, callId: {}, sessionId: {}, duration: {}ms, error: {}", toolName, riskLevel, toolCallId, sessionId, duration, e.getMessage());
            throw e;
        }
    }

    @Override
    public String getName() {
        return "AgentToolAuditInterceptor";
    }

    /**
     * PreToolUse 审计日志
     * IRREVERSIBLE_WRITE 和 DANGEROUS 等级记录完整入参快照；其余等级仅记录调用元信息
     */
    private void logPreToolUse(String toolName, String toolCallId, String sessionId, ToolRiskLevel riskLevel, String arguments) {
        if (riskLevel == ToolRiskLevel.IRREVERSIBLE_WRITE || riskLevel == ToolRiskLevel.DANGEROUS) {
            log.info("[tool-audit] PRE  | tool: {}, risk: {}, callId: {}, sessionId: {}, args: {}", toolName, riskLevel, toolCallId, sessionId, arguments);
        } else {
            log.info("[tool-audit] PRE  | tool: {}, risk: {}, callId: {}, sessionId: {}", toolName, riskLevel, toolCallId, sessionId);
        }
    }

    /**
     * PostToolUse 审计日志
     * 记录耗时和是否异常；IRREVERSIBLE_WRITE 和 DANGEROUS 等级额外记录结果摘要
     */
    private void logPostToolUse(String toolName, String toolCallId, String sessionId, ToolRiskLevel riskLevel, long duration, ToolCallResponse response) {
        boolean isError = response.isError();
        if (riskLevel == ToolRiskLevel.IRREVERSIBLE_WRITE || riskLevel == ToolRiskLevel.DANGEROUS) {
            String resultSummary = truncate(response.getResult());
            log.info("[tool-audit] POST | tool: {}, risk: {}, callId: {}, sessionId: {}, duration: {}ms, error: {}, result: {}", toolName, riskLevel, toolCallId, sessionId, duration, isError, resultSummary);
        } else {
            log.info("[tool-audit] POST | tool: {}, risk: {}, callId: {}, sessionId: {}, duration: {}ms, error: {}", toolName, riskLevel, toolCallId, sessionId, duration, isError);
        }
    }

    /**
     * 截断结果字符串，超出 maxLength 时附加省略标记
     */
    private String truncate(String text) {
        if (text == null) return "null";
        return text.length() <= AgentToolAuditInterceptor.SUMMARY_MAX_LENGTH ? text : text.substring(0, AgentToolAuditInterceptor.SUMMARY_MAX_LENGTH) + "...[truncated]";
    }
}
