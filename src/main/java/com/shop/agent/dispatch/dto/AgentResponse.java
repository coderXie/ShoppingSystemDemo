package com.shop.agent.dispatch.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 执行结果响应 DTO。
 */
@Getter
@AllArgsConstructor
public class AgentResponse {

  private final String status;
  private final List<String> executedNodes;
  private final String latestAiMessage;
  private final boolean requireHumanApproval;
}
