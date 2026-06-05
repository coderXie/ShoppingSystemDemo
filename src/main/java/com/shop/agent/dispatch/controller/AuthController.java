package com.shop.agent.dispatch.controller;

import com.shop.agent.dispatch.domain.auth.entity.SysUser;
import com.shop.agent.dispatch.domain.auth.repository.SysUserRepository;
import com.shop.agent.dispatch.dto.LoginRequest;
import com.shop.agent.dispatch.dto.LoginResponse;
import com.shop.agent.dispatch.infrastructure.security.JwtUtils;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 认证控制器，提供管理员登录接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final SysUserRepository sysUserRepository;
  private final JwtUtils jwtUtils;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  /**
   * 管理员登录。
   *
   * @param request 登录请求（username + password）
   * @return 包含 JWT Token 的响应
   */
  @PostMapping("/login")
  public Mono<ResponseEntity<LoginResponse>> login(@RequestBody LoginRequest request) {
    return Mono.fromCallable(() -> {
          log.info("【认证】收到登录请求，username={}", request.getUsername());

          if (request.getUsername() == null || request.getPassword() == null) {
            return ResponseEntity.ok(LoginResponse.fail("用户名和密码不能为空"));
          }

          Optional<SysUser> userOpt = sysUserRepository.findByUsername(request.getUsername());
          if (userOpt.isEmpty()) {
            log.warn("【认证】用户不存在，username={}", request.getUsername());
            return ResponseEntity.ok(LoginResponse.fail("用户名或密码错误"));
          }

          SysUser user = userOpt.get();

          if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("【认证】密码错误，username={}", request.getUsername());
            return ResponseEntity.ok(LoginResponse.fail("用户名或密码错误"));
          }

          String token = jwtUtils.generateToken(user.getUsername(), user.getRole());
          log.info("【认证】登录成功，username={}, role={}", user.getUsername(), user.getRole());
          return ResponseEntity.ok(LoginResponse.success(token, user.getUsername(), user.getRole()));
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(e -> {
          log.error("【认证】登录处理异常", e);
          return Mono.just(ResponseEntity.ok(LoginResponse.fail("登录处理异常: " + e.getMessage())));
        });
  }
}
