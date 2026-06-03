package com.shop.agent.dispatch.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户聊天请求 DTO。
 */
@Getter
@AllArgsConstructor
public class ChatRequest {

  private final Long orderId;
  private final String userId;
  private final String message;
}
