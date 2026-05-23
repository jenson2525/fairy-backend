package cn.nuaa.jensonxu.fairy.web.aspect;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 接口访问日志切面
 */
@Slf4j
@Aspect
@Component
public class ApiLogAspect {

    private static final int MAX_LOG_TEXT_LENGTH = 1000;

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password",
            "apiKey",
            "token",
            "secretKey",
            "accessKey"
    );

    /**
     * 记录接口请求日志
     */
    @Around("apiLogPointcut()")
    public Object logApi(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes == null ? null : attributes.getRequest();

        String requestUri = request == null ? "unknown" : request.getRequestURI();
        String httpMethod = request == null ? "unknown" : request.getMethod();
        String javaMethod = joinPoint.getSignature().toShortString();
        String requestArgs = buildRequestArgs(joinPoint.getArgs());

        log.info("[api] 请求开始, method: {}, uri: {}, handler: {}, args: {}",
                httpMethod, requestUri, javaMethod, requestArgs);

        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - startTime;
            log.info("[api] 请求完成, method: {}, uri: {}, handler: {}, cost: {}ms, result: {}",
                    httpMethod, requestUri, javaMethod, cost, toLogText(result));
            return result;
        } catch (Throwable throwable) {
            long cost = System.currentTimeMillis() - startTime;
            log.error("[api] 请求异常, method: {}, uri: {}, handler: {}, cost: {}ms",
                    httpMethod, requestUri, javaMethod, cost, throwable);
            throw throwable;
        }
    }

    /**
     * 定义接口日志切点
     */
    @Pointcut("within(cn.nuaa.jensonxu.fairy.web.controller..*)")
    public void apiLogPointcut() {
    }

    /**
     * 构建请求参数日志文本
     */
    private String buildRequestArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }

        List<Object> logArgs = Arrays.stream(args)
                .filter(this::isLoggableArg)
                .map(this::maskSensitiveObject)
                .toList();

        return toLogText(logArgs);
    }

    /**
     * 判断参数是否适合打印日志
     */
    private boolean isLoggableArg(Object arg) {
        return !(arg instanceof MultipartFile
                || arg instanceof HttpServletRequest
                || arg instanceof HttpServletResponse
                || arg instanceof SseEmitter
                || arg instanceof InputStream
                || arg instanceof InputStreamResource);
    }

    /**
     * 对敏感字段进行脱敏
     */
    private Object maskSensitiveObject(Object value) {
        if (value == null) {
            return null;
        }

        Object jsonObject = JSON.toJSON(value);
        maskSensitiveJson(jsonObject);
        return jsonObject;
    }

    /**
     * 递归脱敏 JSON 对象中的敏感字段
     */
    private void maskSensitiveJson(Object value) {
        if (value instanceof JSONObject jsonObject) {
            for (String key : jsonObject.keySet()) {
                Object fieldValue = jsonObject.get(key);
                if (isSensitiveField(key)) {
                    jsonObject.put(key, "***");
                } else {
                    maskSensitiveJson(fieldValue);
                }
            }
            return;
        }

        if (value instanceof JSONArray jsonArray) {
            for (Object item : jsonArray) {
                maskSensitiveJson(item);
            }
        }
    }

    /**
     * 判断字段名是否为敏感字段
     */
    private boolean isSensitiveField(String fieldName) {
        return SENSITIVE_FIELD_NAMES.stream()
                .anyMatch(sensitiveField -> sensitiveField.equalsIgnoreCase(fieldName));
    }

    /**
     * 将对象转换为限制长度后的日志文本
     */
    private String toLogText(Object value) {
        if (value == null) {
            return "null";
        }

        String text;
        try {
            text = JSON.toJSONString(value);
        } catch (Exception e) {
            text = String.valueOf(value);
        }

        if (text.length() <= MAX_LOG_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_LOG_TEXT_LENGTH) + "...";
    }
}