package com.shop.agent.dispatch.dto;

import java.util.List;

/**
 * Agent 执行结果响应 DTO。
 */
public record AgentResponse(
    String status,
    List<String> executedNodes,
    String latestAiMessage,
    boolean requireHumanApproval
) {
}
