package cn.nuaa.jensonxu.fairy.integration.agent.harness;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Harness 调用次数限制配置属性
 * 管理 ToolCallLimitHook 和 ModelCallLimitHook 的上限阈值与超限退出策略。
 * 配置前缀：fairy.agent.harness.limit
 * 示例配置：
 * fairy:
 *   agent:
 *     harness:
 *       limit:
 *         tool-call-thread-limit: 20
 *         model-call-thread-limit: 10
 *         exit-behavior: END
 */
@Data
@ConfigurationProperties(prefix = "harness.limit")
public class HarnessLimitProperties {

    /**
     * 单会话内工具调用次数上限
     * 超出后按 exitBehavior 策略处理
     */
    private Integer toolCallThreadLimit = 20;

    /**
     * 单会话内模型推理次数上限
     * 超出后按 exitBehavior 策略处理
     */
    private Integer modelCallThreadLimit = 10;

    /**
     * 超限退出策略，作用于 ToolCallLimitHook 和 ModelCallLimitHook
     * END：将已有结论作为最终结果正常返回；ERROR：向上抛出异常
     */
    private String exitBehavior = "END";

    /**
     * 工具调用重试配置
     */
    private Retry retry = new Retry();

    @Data
    public static class Retry {

        /**
         * 最大重试次数（不含首次执行）
         * 实际最多执行 maxRetries + 1 次
         */
        private int maxRetries = 2;

        /**
         * 首次重试前的等待时间（毫秒）
         */
        private long initialDelayMs = 500;

        /**
         * 重试间隔的最大上限（毫秒）
         */
        private long maxDelayMs = 5000;

        /**
         * 指数退避系数，每次重试间隔乘以此系数
         */
        private double backoffFactor = 2.0;

        /**
         * 是否在退避时间上叠加随机抖动，避免多个工具调用同时重试时产生流量峰值
         */
        private boolean jitter = true;

        /**
         * 耗尽全部重试次数后的行为
         * RETURN_MESSAGE：将错误信息作为工具结果返回给模型，由模型决策后续行动
         * RAISE：向上抛出异常，终止当前 Agent 执行链路
         */
        private String onFailure = "RETURN_MESSAGE";
    }
}
