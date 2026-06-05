package com.shop.agent.dispatch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模拟订单造数请求 DTO。
 *
 * @param orderId   目标订单 ID
 * @param sceneType 场景类型：OUT_OF_STOCK（缺货审批流）/ NORMAL（正常物流）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MockOrderRequest {

  private Long orderId;

  private String sceneType;
}
