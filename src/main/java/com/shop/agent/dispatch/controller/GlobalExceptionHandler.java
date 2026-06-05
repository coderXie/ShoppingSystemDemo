package com.shop.agent.dispatch.controller;

import com.shop.agent.dispatch.dto.AgentResponse;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

/**
 * 全局异常处理器，统一捕获并格式化所有未处理的异常。
 *
 * <p>所有响应均使用通用错误文案，不向客户端暴露服务端堆栈或异常消息。
 * 详细错误信息仅记录到服务端日志。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String GENERIC_ERROR = "系统繁忙，请稍后再试";

  @ExceptionHandler(ServerWebInputException.class)
  public ResponseEntity<AgentResponse> handleServerWebInputException(ServerWebInputException ex) {
    log.warn("【全局异常】请求解析失败: {}", ex.getMessage());
    return ResponseEntity.badRequest()
        .body(new AgentResponse("ERROR", List.of(), "请求格式有误，请检查后重试", false));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<AgentResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
    log.warn("【全局异常】参数错误: {}", ex.getMessage());
    return ResponseEntity.badRequest()
        .body(new AgentResponse("ERROR", List.of(), GENERIC_ERROR, false));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<AgentResponse> handleIllegalStateException(IllegalStateException ex) {
    log.warn("【全局异常】状态错误: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new AgentResponse("REJECTED", List.of(), GENERIC_ERROR, false));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<AgentResponse> handleAllExceptions(Exception ex) {
    log.error("【全局异常】未预期的错误: {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new AgentResponse("ERROR", List.of(), GENERIC_ERROR, false));
  }
}
