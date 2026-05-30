package cn.nuaa.jensonxu.fairy.integration.agent.harness.risk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.HashMap;

/**
 * Harness 工具风险配置属性
 * 管理无法通过 {@code @ToolRisk} 注解标注的 MCP 工具的风险等级配置。
 * 配置前缀：fairy.agent.harness.risk
 *
 * 示例配置：
 * fairy:
 *   agent:
 *     harness:
 *       risk:
 *         mcp-default: REVERSIBLE_WRITE
 *         mcp-overrides:
 *           send_email: IRREVERSIBLE_WRITE
 *           delete_record: DANGEROUS
 */
@Data
@ConfigurationProperties(prefix = "fairy.agent.harness.risk")
public class HarnessRiskProperties {

    /**
     * MCP 工具的默认风险等级
     * 未在 mcpOverrides 中配置的 MCP 工具均使用此等级
     */
    private ToolRiskLevel mcpDefault = ToolRiskLevel.REVERSIBLE_WRITE;

    /**
     * MCP 工具风险等级覆盖映射表
     * Key：MCP 工具名称（与 MCP Server 注册名一致）
     * Value：对应的风险等级
     */
    private Map<String, ToolRiskLevel> mcpOverrides = new HashMap<>();
}
