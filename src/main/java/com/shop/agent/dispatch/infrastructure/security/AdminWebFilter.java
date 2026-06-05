package com.shop.agent.dispatch.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 管理后台权限拦截器（基于 WebFlux WebFilter）。
 *
 * <p>拦截管理端接口，校验 JWT Token 中的角色是否为 MANAGER。
 * 用户侧接口（如 /api/agent/chat）不受此拦截器影响。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminWebFilter implements WebFilter {

  private final JwtUtils jwtUtils;

  /** 需要管理员权限的路径前缀 */
  private static final List<String> ADMIN_PATHS = List.of(
      "/api/agent/approve",
      "/api/agent/approvals",
      "/api/agent/pending",
      "/api/agent/init-checkpoint"
  );

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String path = request.getURI().getPath();

    // 仅拦截管理端路径
    if (!isAdminPath(path)) {
      return chain.filter(exchange);
    }

    // 提取 Authorization 头
    String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      log.warn("【安全拦截】管理端接口缺少 Token，path={}", path);
      return writeForbidden(exchange, "未登录，请先获取 Token");
    }

    String token = authHeader.substring(7);

    // 校验 Token 有效性
    if (!jwtUtils.validateToken(token)) {
      log.warn("【安全拦截】Token 无效或已过期，path={}", path);
      return writeForbidden(exchange, "Token 无效或已过期，请重新登录");
    }

    // 校验角色
    String role = jwtUtils.getRoleFromToken(token);
    if (!"MANAGER".equals(role)) {
      log.warn("【安全拦截】角色无权限，role={}, path={}", role, path);
      return writeForbidden(exchange, "权限不足，仅管理员可访问");
    }

    // 校验通过，放行
    log.debug("【安全拦截】管理员鉴权通过，path={}", path);
    return chain.filter(exchange);
  }

  private boolean isAdminPath(String path) {
    return ADMIN_PATHS.stream().anyMatch(path::startsWith);
  }

  /**
   * 写入 403 Forbidden 响应（JSON 格式）。
   */
  private Mono<Void> writeForbidden(ServerWebExchange exchange, String message) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(HttpStatus.FORBIDDEN);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

    String body = "{\"status\":\"FORBIDDEN\",\"message\":\"" + message + "\"}";
    DataBuffer buffer = response.bufferFactory()
        .wrap(body.getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }
}
