package com.smarttrip.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<Result<?>> business(BusinessException e) {
    int status = e.getCode();
    if (status < 400 || status > 599) status = 400;
    var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
    if (e instanceof AuthRateLimitException limit)
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(limit.getRetryAfter()));
    return response.body(Result.error(status, e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Result<?>> validation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest().body(Result.error(400, "输入字段格式或长度不符合要求"));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<Result<?>> malformed(Exception e) {
    return ResponseEntity.badRequest().body(Result.error(400, "请求格式错误"));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<Result<?>> duplicate(Exception e) {
    return ResponseEntity.status(409).body(Result.error(409, "记录已经存在，请刷新后重试"));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Result<?>> size(Exception e) {
    return ResponseEntity.status(413).body(Result.error(413, "图片不能超过2MB"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<?>> other(Exception e) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("Request failed: {}", e.getClass().getSimpleName());
    return ResponseEntity.internalServerError().body(Result.error(500, "服务暂时不可用，请稍后重试"));
  }
}
