package com.smarttrip.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. 忽略异步 SSE 超时异常（由 SseEmitter 自身机制管理生命周期）
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.warn("SSE 异步长连接到达正常心跳超时或客户端主动断开");
    }

    // 2. 捕获自定义业务异常
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    // 3. 捕获系统未知异常
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统未知异常: ", e);
        return Result.error(500, "服务器开小差了，请稍后再试");
    }
}