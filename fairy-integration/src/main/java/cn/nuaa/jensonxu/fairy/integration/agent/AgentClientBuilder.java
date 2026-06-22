package cn.nuaa.jensonxu.fairy.integration.agent;

import cn.nuaa.jensonxu.fairy.integration.agent.harness.HarnessLimitProperties;
import cn.nuaa.jensonxu.fairy.integration.agent.harness.audit.AgentToolAuditInterceptor;
import cn.nuaa.jensonxu.fairy.integration.agent.harness.pii.ChinesePIIDetector;
import cn.nuaa.jensonxu.fairy.integration.agent.memory.AgentMemoryRecaller;
import cn.nuaa.jensonxu.fairy.integration.agent.memory.hook.AfterAgentMemoryHook;
import cn.nuaa.jensonxu.fairy.integration.agent.model.manager.CustomModelManager;
import cn.nuaa.jensonxu.fairy.integration.service.mcp.McpClientManager;
import cn.nuaa.jensonxu.fairy.integration.service.mcp.McpToolCallLogger;
import cn.nuaa.jensonxu.fairy.integration.service.tools.service.SkillToolService;

import cn.nuaa.jensonxu.fairy.integration.agent.memory.hook.LongTermMemoryInterceptor;
import cn.nuaa.jensonxu.fairy.integration.agent.memory.AgentLoadedContext;
import cn.nuaa.jensonxu.fairy.integration.agent.memory.AgentLongTermMemory;
import cn.nuaa.jensonxu.fairy.integration.service.skill.NativeSkillRegistry;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.pii.PIIDetectionHook;
import com.alibaba.cloud.ai.graph.agent.hook.pii.PIIType;
import com.alibaba.cloud.ai.graph.agent.hook.pii.RedactionStrategy;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Stream;

/**
 * Agent 构建器
 * 整合模型解析、工具注入、记忆回填，对外提供统一的 ReactAgent 创建入口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentClientBuilder {

    private final CustomModelManager customModelManager;
    private final List<ToolCallbackProvider> toolCallbackProviders;
    private final MemorySaver memorySaver;
    private final AfterAgentMemoryHook afterAgentMemoryHook;
    private final AgentLongTermMemory agentLongTermMemory;
    private final AgentMemoryRecaller agentMemoryRecaller;
    private final McpClientManager mcpClientManager;  // mcp 配置管理
    private final NativeSkillRegistry nativeSkillRegistry;  // skill 注册
    private final List<SkillToolService> skillToolServices;  // skill 列表
    private final HarnessLimitProperties harnessLimitProperties;
    private final AgentToolAuditInterceptor agentToolAuditInterceptor;
    private final ChinesePIIDetector chinesePIIDetector;

    /**
     * 根据请求上下文构建 ReactAgent
     *
     * @param modelName 请求指定的模型名称，为空时回退到 defaultModel
     * @param sessionId 会话 ID（agentSessionId），用于 MemorySaver 的 threadId 隔离
     * @param userId    用户 ID，用于创建 LongTermMemoryInterceptor
     * @param context   由 AgentMemoryManager.loadContext() 加载的记忆上下文
     * @return 已注入工具、记忆、System Prompt 的 ReactAgent 实例
     */
    public ReactAgent build(String modelName, String sessionId, String userId, AgentLoadedContext context) {
        if (StringUtils.isBlank(modelName)) {
            throw new IllegalArgumentException("[agent] modelName 不能为空，请先在模型配置中选择一个模型");
        }
        log.info("[agent] 构建 ReactAgent, modelName: {}, sessionId: {}", modelName, sessionId);
        prepopulateIfNeeded(sessionId, context.shortTermMessages());  // 若 MemorySaver 中尚无该会话的记录（进程重启），从 Redis/MySQL 回填历史消息
        LongTermMemoryInterceptor memInterceptor = new LongTermMemoryInterceptor(agentLongTermMemory, agentMemoryRecaller, userId);

        // 组装 skill
        SkillsAgentHook skillsAgentHook = SkillsAgentHook.builder()
                .skillRegistry(nativeSkillRegistry)
                .groupedTools(buildGroupedTools())
                .build();

        // 组装mcp
        ToolCallback[] localTools = toolCallbackProviders.stream()
                .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
        ToolCallback[] nacosTools = Arrays.stream(mcpClientManager.getActiveToolCallbacks())
                .map(McpToolCallLogger::new)
                .toArray(ToolCallback[]::new);
        // 将 TodoListInterceptor 提供的 WriteTodosTool 一并注入，Agent 才能主动更新任务状态
        ToolCallback[] allTools = Stream.concat(Arrays.stream(localTools), Arrays.stream(nacosTools)).toArray(ToolCallback[]::new);

        // 组装各种拦截器、过滤器
        List<Hook> hooks = List.of(skillsAgentHook, afterAgentMemoryHook, buildToolCallLimitHook(), buildModelCallLimitHook(), buildPIIDetectionHook());
        List<Interceptor> interceptors = List.of(memInterceptor, agentToolAuditInterceptor, buildToolRetryInterceptor(), buildTodoListInterceptor());
        return customModelManager.createAgent(modelName, userId, allTools, memorySaver, hooks, interceptors);
    }

    /**
     * 当 MemorySaver 中不存在该 sessionId 的历史时，从 Redis/MySQL 加载的消息重建 Checkpoint
     * 用于应对服务重启后 MemorySaver 内存状态丢失的场景
     */
    private void prepopulateIfNeeded(String sessionId, List<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
            if (memorySaver.get(config).isEmpty()) {
                Checkpoint checkpoint = Checkpoint.builder()
                        .id(UUID.randomUUID().toString())
                        .state(Map.of("messages", messages))
                        .nodeId(StateGraph.START)          // 回填的是执行前的起始状态
                        .nextNodeId(StateGraph.START)      // 框架要求非空；正常对话走 start 分支不据此路由
                        .build();
                memorySaver.put(config, checkpoint);
                log.info("[agent] MemorySaver 回填历史消息 {} 条, sessionId: {}", messages.size(), sessionId);
            }
        } catch (Exception e) {
            log.warn("[agent] MemorySaver 回填失败, sessionId: {}", sessionId, e);
        }
    }

    /**
     * 将 SkillToolService 实现类按 skillName 分组，构建 groupedTools
     */
    private Map<String, List<ToolCallback>> buildGroupedTools() {
        Map<String, List<ToolCallback>> groupedTools = new HashMap<>();
        for (SkillToolService skillTool : skillToolServices) {
            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(skillTool)
                    .build()
                    .getToolCallbacks();
            groupedTools.computeIfAbsent(skillTool.getSkillName(), k -> new ArrayList<>()).addAll(Arrays.asList(callbacks));
        }
        log.info("[skill] groupedTools 构建完成，共 {} 个 skill 绑定工具组", groupedTools.size());
        return groupedTools;
    }

    /**
     * 构建 ToolCallLimitHook，限制单会话内工具调用总次数
     * 防止 Agent 进入工具调用无限循环
     */
    private ToolCallLimitHook buildToolCallLimitHook() {
        ToolCallLimitHook.ExitBehavior behavior = ToolCallLimitHook.ExitBehavior.valueOf(harnessLimitProperties.getExitBehavior());
        return ToolCallLimitHook.builder()
                .threadLimit(harnessLimitProperties.getToolCallThreadLimit())
                .exitBehavior(behavior)
                .build();
    }

    /**
     * 构建 ModelCallLimitHook，限制单会话内模型推理总次数
     * 防止 Agent 进入无意义的无限推理循环
     */
    private ModelCallLimitHook buildModelCallLimitHook() {
        ModelCallLimitHook.ExitBehavior behavior = ModelCallLimitHook.ExitBehavior.valueOf(harnessLimitProperties.getExitBehavior());
        return ModelCallLimitHook.builder()
                .threadLimit(harnessLimitProperties.getModelCallThreadLimit())
                .exitBehavior(behavior)
                .build();
    }

    /**
     * 构建 ToolRetryInterceptor，工具调用失败时由 Harness 自动按指数退避策略重试
     * 耗尽重试次数后以 RETURN_MESSAGE 策略将错误信息返回模型，由模型决策后续行动
     */
    private ToolRetryInterceptor buildToolRetryInterceptor() {
        HarnessLimitProperties.Retry retryConfig = harnessLimitProperties.getRetry();
        return ToolRetryInterceptor.builder()
                .maxRetries(retryConfig.getMaxRetries())
                .initialDelay(retryConfig.getInitialDelayMs())
                .maxDelay(retryConfig.getMaxDelayMs())
                .backoffFactor(retryConfig.getBackoffFactor())
                .jitter(retryConfig.isJitter())
                .retryOn(Exception.class)
                .onFailure(ToolRetryInterceptor.OnFailureBehavior.valueOf(retryConfig.getOnFailure()))
                .build();
    }

    /**
     * 构建 PIIDetectionHook，在 Agent 消息管道中自动检测并脱敏个人敏感信息
     * 作用范围：用户输入（applyToInput）+ 工具返回结果（applyToToolResults）
     * 脱敏策略：MASK（以 * 替换，保留文本结构，不影响模型对上下文的理解）
     * 不作用于模型输出（applyToOutput=false），避免误改模型的合理回复内容
     */
    private PIIDetectionHook buildPIIDetectionHook() {
        return PIIDetectionHook.builder()
                .piiType(PIIType.CUSTOM)
                .detector(chinesePIIDetector)
                .strategy(RedactionStrategy.MASK)
                .applyToInput(true)
                .applyToOutput(false)
                .applyToToolResults(true)
                .build();
    }

    /**
     * 构建 TodoListInterceptor，在每次模型调用前将当前任务清单注入 System Prompt
     * Agent 可通过 WriteTodosTool 主动创建和更新任务状态，实现跨轮次的多步骤任务追踪
     */
    private TodoListInterceptor buildTodoListInterceptor() {
        return TodoListInterceptor.builder()
                .systemPrompt("""
                    当用户的请求涉及多个步骤或子任务时，请在开始执行前使用 write_todos 工具创建任务清单。
                    在执行过程中，每完成一个步骤后及时更新对应任务的状态。
                    任务状态说明：
                    - pending：待执行
                    - in_progress：执行中
                    - completed：已完成
                    在回复用户之前，请确保所有相关任务都已完成并标记为 completed 状态。
                    对于简单的单步骤任务，无需创建任务清单。
                    """)
                .build();
    }
}
