package cn.nuaa.jensonxu.fairy.common.exception;

/**
 * 业务异常
 */
public class BusinessException extends RuntimeException {

    private final Integer code;

    /**
     * 根据错误消息创建业务异常
     */
    public BusinessException(String message) {
        super(message);
        this.code = ErrorCode.BUSINESS_ERROR.getCode();
    }

    /**
     * 根据错误码创建业务异常
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 根据错误码和自定义错误消息创建业务异常
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /**
     * 获取业务异常对应的响应码
     */
    public Integer getCode() {
        return code;
    }
}