package cn.nuaa.jensonxu.fairy.integration.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 全局配置属性
 * 绑定 application.yml 中 fairy.agent.* 配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "fairy.agent")
public class AgentProperties {

    /**
     * 全局默认模型名称
     * 请求中 modelName 为空时使用此值
     */
    private String defaultModel = "qwen3.5-plus";

    /**
     * ReAct 最大循环迭代次数上限
     */
    private int maxIterations = 10;

    /**
     * 是否将 LLM 推理过程（Thought）推送给客户端
     */
    private boolean streamThinking = true;

    /**
     * Agent 会话历史保留的最大轮次
     */
    private int maxHistorySize = 10;

    /**
     * 记忆管理配置
     */
    private Memory memory = new Memory();

    /**
     * 并发控制配置
     */
    private Concurrency concurrency = new Concurrency();

    @Data
    public static class Memory {

        private ShortTerm shortTerm = new ShortTerm();
        private LongTerm longTerm = new LongTerm();

        @Data
        public static class ShortTerm {

            /** Redis 滑动窗口大小（条） */
            private int maxMessages = 20;

            /** Redis key 过期时间（小时） */
            private int ttlHours = 24;
        }

        @Data
        public static class LongTerm {

            /** 触发摘要的消息数阈值 */
            private int summarizeThreshold = 20;

            /** MySQL 中每用户保留的最大记忆条数 */
            private int maxFactsPerUser = 50;

            /** 摘要专用模型名称 */
            private String modelName = "deepseek-v4-flash";
        }
    }

    /**
     * 并发控制配置
     */
    @Data
    public static class Concurrency {
        /**
         * 最大并发 Agent 请求数（Semaphore 许可数）
         * 超过此上限的请求将等待，等待超时后拒绝
         */
        private int maxConcurrent = 20;

        /**
         * 等待获取许可的最长时间（毫秒）
         * 超过此时间仍未获取到许可，则直接向客户端返回 429 错误事件
         */
        private long acquireTimeoutMs = 5000;

        /**
         * 分布式信号量许可的持有 TTL（毫秒）
         * 即单个 Agent 请求允许执行的最长时间上限
         * 超过此时间许可将被 Redis 自动回收，防止节点崩溃导致的许可永久泄漏
         * 建议设置为实际最长执行时间的 1.5~2 倍，默认 60 秒
         */
        private long permitTtlMs = 60000;
    }
}