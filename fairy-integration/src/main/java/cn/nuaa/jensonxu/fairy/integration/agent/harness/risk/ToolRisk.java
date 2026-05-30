package cn.nuaa.jensonxu.fairy.integration.agent.harness.risk;

import java.lang.annotation.*;

/**
 * 工具风险等级注解
 * 用于标注本地工具类或工具方法（{@code @Tool} 注解的方法）的风险等级。
 * 支持类级别和方法级别两种标注方式：
 * <ul>
 *   <li>类级别：该类下所有工具方法统一使用此等级</li>
 *   <li>方法级别：覆盖类级别声明，适用于同一工具类中不同方法风险等级不同的场景</li>
 * </ul>
 * MCP 工具不支持注解标注，其风险等级通过 {@code ToolRiskRegistry} 的配置映射表管理。
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolRisk {
    /**
     * 声明工具的风险等级
     */
    ToolRiskLevel value();

    /**
     * 定级依据说明
     * 建议在 IRREVERSIBLE_WRITE 和 DANGEROUS 级别时填写，便于审查和维护
     */
    String reason() default "";
}
