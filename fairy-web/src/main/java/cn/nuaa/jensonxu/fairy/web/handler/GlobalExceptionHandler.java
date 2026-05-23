package cn.nuaa.jensonxu.fairy.web.handler;

import cn.nuaa.jensonxu.fairy.common.data.file.response.CustomResponse;
import cn.nuaa.jensonxu.fairy.common.exception.BusinessException;
import cn.nuaa.jensonxu.fairy.common.exception.ErrorCode;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String DEFAULT_PARAM_ERROR_MESSAGE = "请求参数错误";

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public CustomResponse<String> handleBusinessException(BusinessException e) {
        log.warn("[exception] 业务异常, code: {}, message: {}", e.getCode(), e.getMessage());
        return CustomResponse.build(e.getCode(), e.getMessage(), null);
    }

    /**
     * 处理参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public CustomResponse<String> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("[exception] 参数异常, message: {}", e.getMessage());
        return CustomResponse.build(ErrorCode.PARAM_ERROR.getCode(), e.getMessage(), null);
    }

    /**
     * 处理请求体参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CustomResponse<String> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(DEFAULT_PARAM_ERROR_MESSAGE);

        log.warn("[exception] 请求体参数校验失败, message: {}", message);
        return CustomResponse.build(ErrorCode.PARAM_ERROR.getCode(), message, null);
    }

    /**
     * 处理表单参数绑定异常
     */
    @ExceptionHandler(BindException.class)
    public CustomResponse<String> handleBindException(BindException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(DEFAULT_PARAM_ERROR_MESSAGE);

        log.warn("[exception] 表单参数绑定失败, message: {}", message);
        return CustomResponse.build(ErrorCode.PARAM_ERROR.getCode(), message, null);
    }

    /**
     * 处理请求参数校验异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public CustomResponse<String> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations()
                .stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(DEFAULT_PARAM_ERROR_MESSAGE);

        log.warn("[exception] 请求参数校验失败, message: {}", message);
        return CustomResponse.build(ErrorCode.PARAM_ERROR.getCode(), message, null);
    }

    /**
     * 处理兜底系统异常
     */
    @ExceptionHandler(Exception.class)
    public CustomResponse<String> handleException(Exception e) {
        log.error("[exception] 系统异常", e);
        return CustomResponse.build(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage(), null);
    }
}