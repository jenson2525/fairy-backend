package cn.nuaa.jensonxu.fairy.common.exception;

/**
 * 统一错误码枚举类
 */
public enum ErrorCode {

    SUCCESS(200, "操作成功"),
    PARAM_ERROR(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),
    BUSINESS_ERROR(500, "业务处理失败"),
    SYSTEM_ERROR(500, "系统异常，请稍后重试");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 获取错误码
     */
    public Integer getCode() {
        return code;
    }

    /**
     * 获取默认错误提示
     */
    public String getMessage() {
        return message;
    }
}