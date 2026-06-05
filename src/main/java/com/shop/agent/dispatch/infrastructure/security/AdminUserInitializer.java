package com.shop.agent.dispatch.infrastructure.security;

import com.shop.agent.dispatch.domain.auth.entity.SysUser;
import com.shop.agent.dispatch.domain.auth.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 应用启动时自动初始化管理员账号（如不存在）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements CommandLineRunner {

  private final SysUserRepository sysUserRepository;

  @Override
  public void run(String... args) {
    if (sysUserRepository.findByUsername("admin").isEmpty()) {
      BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
      String encodedPassword = encoder.encode("admin123");

      SysUser admin = SysUser.builder()
          .username("admin")
          .password(encodedPassword)
          .role("MANAGER")
          .build();
      sysUserRepository.save(admin);
      log.info("【初始化】管理员账号已创建: admin / admin123 (BCrypt)");
    } else {
      log.info("【初始化】管理员账号已存在，跳过创建");
    }
  }
}
