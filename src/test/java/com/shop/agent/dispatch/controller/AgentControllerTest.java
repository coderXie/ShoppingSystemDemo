package com.shop.agent.dispatch.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.shop.agent.dispatch.domain.agent.service.AgentSessionService;
import com.shop.agent.dispatch.dto.AgentResponse;
import com.shop.agent.dispatch.dto.ApproveRequest;
import com.shop.agent.dispatch.dto.ChatRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * AgentController WebFlux 切片测试。
 *
 * <p>只加载 Web 层和指定的 Controller，其余依赖通过 @MockBean 注入。
 * 不需要加载完整的 Spring 上下文，因此不受 JPA / LangGraph4j 初始化影响。</p>
 */
@WebFluxTest(AgentController.class)
@ActiveProfiles("test")
class AgentControllerTest {

  @Autowired
  private WebTestClient webTestClient;

  @MockBean
  private AgentSessionService agentSessionService;

  @Test
  @DisplayName("POST /api/agent/chat 应返回 AgentResponse")
  void chat_shouldReturnAgentResponse() {
    // given
    AgentResponse mockResponse = new AgentResponse(
        "COMPLETED",
        List.of("customerServiceNode"),
        "您好，有什么可以帮您？",
        false
    );
    when(agentSessionService.chat(any(), any(), any())).thenReturn(mockResponse);

    ChatRequest request = new ChatRequest(1001L, "u123", "你好");

    // when & then
    webTestClient.post()
        .uri("/api/agent/chat")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("COMPLETED")
        .jsonPath("$.latestAiMessage").isEqualTo("您好，有什么可以帮您？")
        .jsonPath("$.requireHumanApproval").isEqualTo(false);
  }

  @Test
  @DisplayName("POST /api/agent/approve 应返回审批结果")
  void approve_shouldReturnAgentResponse() {
    // given
    AgentResponse mockResponse = new AgentResponse(
        "COMPLETED",
        List.of("supervisorNode"),
        "主管已审批通过，订单状态已更新为：已同意(APPROVED)",
        false
    );
    when(agentSessionService.approve(any(), any(), any())).thenReturn(mockResponse);

    ApproveRequest request = new ApproveRequest(1001L, "APPROVED", "同意退款，库存已核实");

    // when & then
    webTestClient.post()
        .uri("/api/agent/approve")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("COMPLETED")
        .jsonPath("$.latestAiMessage").isEqualTo("主管已审批通过，订单状态已更新为：已同意(APPROVED)")
        .jsonPath("$.requireHumanApproval").isEqualTo(false);
  }
}
