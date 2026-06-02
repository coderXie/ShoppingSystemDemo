package com.shop.agent.dispatch.dto;

/**
 * 用户聊天请求 DTO。
 */
public record ChatRequest(Long orderId, String userId, String message) {
}
