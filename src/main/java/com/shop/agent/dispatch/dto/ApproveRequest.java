package com.shop.agent.dispatch.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 管理员审批请求 DTO。
 */
@Getter
@AllArgsConstructor
public class ApproveRequest {

  private final Long orderId;
  private final String decision;
  private final String comment;
}
