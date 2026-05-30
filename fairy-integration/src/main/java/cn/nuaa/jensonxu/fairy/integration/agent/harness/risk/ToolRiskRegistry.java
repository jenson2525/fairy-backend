package cn.nuaa.jensonxu.fairy.integration.agent.harness.risk;

import com.alibaba.nacos.common.utils.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRiskRegistry implements SmartInitializingSingleton, ApplicationContextAware {

    private static final ToolRiskLevel LOCAL_DEFAULT = ToolRiskLevel.READ_ONLY;
    private static final ToolRiskLevel MCP_DEFAULT = ToolRiskLevel.REVERSIBLE_WRITE;

    private ApplicationContext applicationContext;

    private final HarnessRiskProperties riskProperties;
    private final Map<String, ToolRiskLevel> localToolRiskMap = new ConcurrentHashMap<>();

    @Override
    public void afterSingletonsInstantiated() {
        scanLocalTools();
        log.info("[harness-risk] 工具风险注册完成，共扫描 {} 个本地工具", localToolRiskMap.size());
    }

    /**
     * 由 Spring 容器回调注入 ApplicationContext
     */
    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 查询工具的风险等级
     * 查询优先级：本地工具注解 > MCP 配置覆盖 > MCP 默认等级
     *
     * @param toolName 工具名称，与 {@code @Tool.name()} 或方法名一致（本地工具）或与 MCP Server 注册名一致（MCP 工具）
     * @return 对应的风险等级，不会返回 null
     */
    public ToolRiskLevel getRiskLevel(String toolName) {
        ToolRiskLevel local = localToolRiskMap.get(toolName);  // 1. 本地工具（含无注解本地工具，已在扫描时填入 LOCAL_DEFAULT）
        if (local != null) {
            return local;
        }

        ToolRiskLevel mcpOverride = riskProperties.getMcpOverrides().get(toolName);  // 2. MCP 工具显式配置覆盖
        if (mcpOverride != null) {
            return mcpOverride;
        }

        ToolRiskLevel mcpDefault = riskProperties.getMcpDefault();  // 3. MCP 工具默认等级
        return mcpDefault != null ? mcpDefault : MCP_DEFAULT;
    }

    /**
     * 扫描所有 Spring Bean，提取含 {@code @Tool} 注解方法并注册其风险等级
     * 使用 AopUtils 穿透 CGLIB 代理获取真实类，避免注解读取失败
     */
    private void scanLocalTools() {
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception e) {
                continue;
            }

            Class<?> beanClass = AopUtils.getTargetClass(bean);  // 返回原始的类, 而不是代理类
            ToolRisk classRisk = AnnotationUtils.findAnnotation(beanClass, ToolRisk.class);

            for (Method method : beanClass.getMethods()) {
                Tool toolAnnotation = AnnotationUtils.findAnnotation(method, Tool.class);  // 通过反射找到所有 @Tool 修饰的工具类
                if (toolAnnotation == null) {
                    continue;
                }

                String toolName = StringUtils.hasText(toolAnnotation.name()) ? toolAnnotation.name() : method.getName();  // 工具名优先取 @Tool.name()，为空时取方法名
                ToolRisk methodRisk = AnnotationUtils.findAnnotation(method, ToolRisk.class);
                ToolRiskLevel level = methodRisk != null ? methodRisk.value() : classRisk != null ? classRisk.value() : LOCAL_DEFAULT;  // 方法级 @ToolRisk 优先于类级，均无时取 LOCAL_DEFAULT

                localToolRiskMap.put(toolName, level);
                log.debug("[harness-risk] 注册本地工具: {} → {}", toolName, level);
            }
        }
    }
}
