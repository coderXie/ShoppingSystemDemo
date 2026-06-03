package com.shop.agent.dispatch.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.agent.dispatch.domain.agent.entity.AgentCheckpoint;
import com.shop.agent.dispatch.domain.agent.entity.ApprovalLog;
import com.shop.agent.dispatch.domain.agent.repository.AgentCheckpointRepository;
import com.shop.agent.dispatch.domain.agent.repository.ApprovalLogRepository;
import com.shop.agent.dispatch.domain.agent.service.AgentSessionService;
import com.shop.agent.dispatch.domain.order.entity.Order;
import com.shop.agent.dispatch.domain.order.repository.OrderRepository;
import com.shop.agent.dispatch.dto.AgentResponse;
import com.shop.agent.dispatch.dto.ApproveRequest;
import com.shop.agent.dispatch.dto.ChatRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI Agent 调度控制器，提供用户聊天与人工审批两个核心接口。
 *
 * <p>基于 WebFlux 响应式栈，将阻塞的图执行逻辑调度到弹性线程池，
 * 避免阻塞 Netty 事件循环线程。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

  private final AgentSessionService agentSessionService;
  private final ApprovalLogRepository approvalLogRepository;
  private final AgentCheckpointRepository checkpointRepository;
  private final OrderRepository orderRepository;
  private final ObjectMapper objectMapper;

  /**
   * 【临时】初始化测试 Checkpoint，用于绕过聊天步骤直接测试审批。
   */
  @GetMapping("/init-checkpoint")
  public Mono<ResponseEntity<String>> initCheckpoint() {
    return Mono.fromCallable(() -> {
          String threadId = "order-3";
          String json = "[{\"id\":\"cp-1\",\"state\":{\"orderId\":3,\"userId\":\"u1003\",\"messages\":[],\"currentDepartment\":\"SUPERVISOR\",\"contextData\":{},\"requireHumanApproval\":true},\"nodeId\":\"supervisorNode\",\"nextNodeId\":\"supervisorNode\"}]";

          AgentCheckpoint cp = AgentCheckpoint.builder()
              .threadId(threadId)
              .checkpointJson(json)
              .updateTime(java.time.LocalDateTime.now())
              .build();
          checkpointRepository.save(cp);

          // 确保订单状态为 REFUND_PENDING
          orderRepository.findById(3L).ifPresent(order -> {
            order.setStatus("REFUND_PENDING");
            orderRepository.save(order);
          });
          approvalLogRepository.findByOrderId(3L).stream().findFirst().ifPresent(log -> {
            log.setStatus("PENDING");
            log.setManagerComment(null);
            approvalLogRepository.save(log);
          });

          return "测试 Checkpoint 已初始化，orderId=3";
        })
        .subscribeOn(Schedulers.boundedElastic())
        .map(ResponseEntity::ok)
        .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().body("初始化失败: " + e.getMessage())));
  }

  @ExceptionHandler(ServerWebInputException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Mono<String> handleInputException(ServerWebInputException ex) {
    log.error("【API】请求解析失败: {}", ex.getMessage(), ex);
    return Mono.just("请求解析失败: " + ex.getMessage());
  }

  /**
   * 用户聊天接口。
   *
   * <p>接收用户消息，启动新会话或继续已有会话执行 LangGraph 工作流。
   * 若流程进入主管审批节点前中断，返回 {@code status=INTERRUPTED}
   * 与 {@code requireHumanApproval=true}，前端应提示等待人工审核。</p>
   *
   * @param request 聊天请求
   * @return 执行结果
   */
  @PostMapping("/chat")
  public Mono<ResponseEntity<AgentResponse>> chat(@RequestBody ChatRequest request) {
    return Mono.fromCallable(() -> {
          log.info("【API】收到聊天请求，orderId={}", request.getOrderId());
          return agentSessionService.chat(request.getOrderId(), request.getUserId(), request.getMessage());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .map(ResponseEntity::ok)
        .doOnError(e -> log.error("【API】聊天处理失败", e))
        .onErrorResume(e -> Mono.just(ResponseEntity.badRequest()
            .body(new AgentResponse("ERROR", List.of(), e.getMessage(), false))));
  }

  /**
   * 查询待人工审批列表。
   *
   * @return 状态为 PENDING 的审批日志列表
   */
  @GetMapping("/pending")
  public Mono<ResponseEntity<List<ApprovalLog>>> getPendingList() {
    return Mono.fromCallable(() -> {
          log.info("【API】查询待审批列表");
          return approvalLogRepository.findByStatus("PENDING");
        })
        .subscribeOn(Schedulers.boundedElastic())
        .map(ResponseEntity::ok)
        .doOnError(e -> log.error("【API】查询待审批列表失败", e))
        .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().body(List.of())));
  }

  /**
   * 管理员审批接口。
   *
   * <p>接收管理员的审批意见，将意见写入图状态的 {@code contextData}，
   * 然后恢复（Resume）被中断的图执行，走完后续的退款操作。</p>
   *
   * @param request 审批请求
   * @return 执行结果
   */
  @PostMapping("/approve")
  public Mono<ResponseEntity<AgentResponse>> approve(@RequestBody ApproveRequest request) {
    return Mono.fromCallable(() -> {
          log.info("【API】收到审批请求，orderId={}, decision={}",
              request.getOrderId(), request.getDecision());
          return agentSessionService.approve(request.getOrderId(), request.getDecision(), request.getComment());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .map(ResponseEntity::ok)
        .doOnError(e -> log.error("【API】审批处理失败", e))
        .onErrorResume(e -> Mono.just(ResponseEntity.badRequest()
            .body(new AgentResponse("ERROR", List.of(), e.getMessage(), false))));
  }
}
