package cn.nuaa.jensonxu.fairy.integration.service.tools.service.impl;

import cn.nuaa.jensonxu.fairy.integration.service.tools.service.McpToolService;
import lombok.extern.slf4j.Slf4j;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.ValidationResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CalculatorService implements McpToolService {

    @Tool(description = """
        Evaluate a mathematical expression and return the result.
        Supports basic arithmetic (+, -, *, /, %), power (^), sqrt(), abs(),
        log(), log2(), log10(), sin(), cos(), tan(), asin(), acos(), atan(),
        floor(), ceil(), round(). Built-in constants: PI, E.
        """)
    public String calculate(
            @ToolParam(description = "Mathematical expression to evaluate, e.g. 'sqrt(2) * 3', '2^10', 'sin(PI/2)'")
            String expression) {
        log.info("[calculator] >>> 计算表达式: {}", expression);
        try {
            Expression exp = new ExpressionBuilder(expression)
                    .variables("PI", "E")
                    .build()
                    .setVariable("PI", Math.PI)
                    .setVariable("E", Math.E);

            ValidationResult result = exp.validate();
            if (!result.isValid()) {
                return "Expression is invalid: " + String.join("; ", result.getErrors());
            }

            double value = exp.evaluate();
            if (Double.isNaN(value)) {
                return ">>> The expression result is NaN. Please check if the input is mathematically valid.";
            }
            if (Double.isInfinite(value)) {
                return ">>> The expression result is infinite (possible division by zero).";
            }

            String formatted = (value == Math.floor(value) && !Double.isInfinite(value))
                    ? String.valueOf((long) value)
                    : String.valueOf(value);

            log.info("[calculator] <<< 计算结果: {}", formatted);
            return String.format("The result of '%s' is %s", expression, formatted);
        } catch (Exception e) {
            log.warn("[calculator] <<< 表达式解析失败: {}, 原因: {}", expression, e.getMessage());
            return "Failed to evaluate expression: " + e.getMessage();
        }
    }
}