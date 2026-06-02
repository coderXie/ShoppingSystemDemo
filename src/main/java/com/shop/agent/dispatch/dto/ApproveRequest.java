package com.shop.agent.dispatch.dto;

/**
 * 管理员审批请求 DTO。
 */
public record ApproveRequest(Long orderId, String decision, String comment) {
}
