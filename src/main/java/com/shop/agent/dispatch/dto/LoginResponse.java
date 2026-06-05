package com.shop.agent.dispatch.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResponse {

  private final String status;
  private final String token;
  private final String username;
  private final String role;
  private final String message;

  public static LoginResponse success(String token, String username, String role) {
    return new LoginResponse("OK", token, username, role, "登录成功");
  }

  public static LoginResponse fail(String message) {
    return new LoginResponse("FAIL", null, null, null, message);
  }
}
