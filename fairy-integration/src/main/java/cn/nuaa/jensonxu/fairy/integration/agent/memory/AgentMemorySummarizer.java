package cn.nuaa.jensonxu.fairy.integration.agent.memory;

import cn.nuaa.jensonxu.fairy.common.data.llm.ModelConfig;
import cn.nuaa.jensonxu.fairy.integration.agent.AgentProperties;
import cn.nuaa.jensonxu.fairy.integration.agent.model.manager.NativeModelManager;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 记忆摘要提炼器
 * 从对话消息中提炼值得长期保存的结构化记忆条目
 * 由 AgentMemoryExtractConsumer 在每轮结束后异步调用，不写数据库，结果由调用方处理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMemorySummarizer {

    private final NativeModelManager nativeModelManager;
    private final AgentProperties agentProperties;

    private static final String SYSTEM_PROMPT = """
            你是一个对话记忆提炼助手。请从以下用户与 AI 的对话中，提取值得长期记忆的关键信息。

            【记忆分类说明】
            - user（用户背景）：用户的客观背景信息，如职业、技术栈、所在城市、使用语言等跨会话不变的个人特征
            - feedback（行为反馈）：用户对 AI 行为的明确评价或偏好，包括：
              · 回复风格：长度（简洁/详细）、语气（正式/随意）、是否分点、是否举例
              · 格式偏好：是否使用 Markdown、表格、代码块
              · 明确表达过"不要这样做"或"我希望你这样做"的行为约束
            - project（项目约定）：当前项目相关的技术决策、架构约定、命名规范、构建命令等
            - reference（参考资料）：用户提及的外部文档链接、工具名称、API 参考等资源指针

            【提取规则】
            1. 只提取有明确依据的信息，不猜测、不推断
            2. 忽略问候语、闲聊、通用知识问答等无实质记忆价值的内容
            3. key：全局唯一的英文标签，用下划线分隔，如 user_tech_stack、reply_style_preference
            4. name：2-6 个字的简短中文名称，如"技术栈背景"、"回复风格偏好"
            5. description：一句话摘要，不超过 30 字，用于索引展示
            6. content：完整记忆内容，不超过 100 字，表述客观、不带主观判断
            7. 严格输出 JSON 数组，不包含任何额外文字：
               [{"key":"...","name":"...","description":"...","category":"user|feedback|project|reference","content":"..."}, ...]
            8. 若对话中无任何值得记忆的内容，输出空数组：[]
            """;

    /**
     * 对消息列表调用模型进行摘要提炼，返回结构化记忆条目列表
     * 调用方负责将结果写入 MySQL
     *
     * @param messages  待提炼的消息列表
     * @param userId    用户 ID（用于日志）
     * @param sessionId 来源会话 ID
     * @return 提炼出的记忆条目列表，失败或无内容时返回空列表
     */
    public List<SummaryItem> summarize(List<Message> messages, String userId, String sessionId) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        String modelName = agentProperties.getMemory().getLongTerm().getModelName();
        ModelConfig modelConfig = nativeModelManager.getSummaryModelConfig(modelName);

        log.info("[summarizer] 开始提炼, userId: {}, sessionId: {}, 消息数: {}, 模型: {}", userId, sessionId, messages.size(), modelConfig.getModelName());
        try {
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .apiKey(modelConfig.getApiKey())
                    .baseUrl(modelConfig.getBaseUrl())
                    .build();

            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(modelConfig.getModelName())
                            .build())
                    .build();

            String result = ChatClient.builder(chatModel).build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildUserContent(messages))
                    .call()
                    .content();

            List<SummaryItem> items = parseResult(result);
            log.info("[summarizer] 提炼完成, userId: {}, sessionId: {}, 提炼出 {} 条", userId, sessionId, items.size());
            return items;

        } catch (Exception e) {
            log.error("[summarizer] 提炼失败, userId: {}, sessionId: {}", userId, sessionId, e);
            return List.of();
        }
    }

    private String buildUserContent(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String role = MessageType.ASSISTANT.equals(msg.getMessageType()) ? "AI" : "用户";
            sb.append(role).append(": ").append(msg.getText()).append("\n");
        }
        return sb.toString();
    }

    private List<SummaryItem> parseResult(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end < 0 || start >= end) {
            log.warn("[summarizer] 模型返回非 JSON 格式内容: {}", json);
            return List.of();
        }
        try {
            JSONArray array = JSON.parseArray(json.substring(start, end + 1));
            List<SummaryItem> items = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String key = obj.getString("key");
                String name = obj.getString("name");
                String description = obj.getString("description");
                String category = obj.getString("category");
                String content = obj.getString("content");

                if (StringUtils.isBlank(category) || !isValidCategory(category)) {
                    category = "user";
                }
                if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(content)) {
                    items.add(new SummaryItem(key, name, description, category, content));
                }
            }
            return items;
        } catch (Exception e) {
            log.warn("[summarizer] JSON 解析失败, 原始内容: {}", json, e);
            return List.of();
        }
    }

    private boolean isValidCategory(String category) {
        return "user".equals(category) || "feedback".equals(category) || "project".equals(category) || "reference".equals(category);
    }

    /**
     * 摘要提炼结果的值对象
     */
    public record SummaryItem(String key, String name, String description,
                              String category, String content) {}
}