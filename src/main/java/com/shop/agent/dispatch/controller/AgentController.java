package com.shop.agent.dispatch.controller;

import com.shop.agent.dispatch.domain.agent.entity.ApprovalLog;
import com.shop.agent.dispatch.domain.agent.event.UserSessionManager;
import com.shop.agent.dispatch.domain.agent.repository.AgentCheckpointRepository;
import com.shop.agent.dispatch.domain.agent.repository.ApprovalLogRepository;
import com.shop.agent.dispatch.domain.agent.service.AgentSessionService;
import com.shop.agent.dispatch.domain.order.repository.OrderRepository;
import com.shop.agent.dispatch.dto.AgentResponse;
import com.shop.agent.dispatch.dto.ApproveRequest;
import com.shop.agent.dispatch.dto.ChatRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
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
  private final UserSessionManager userSessionManager;
  private final ApprovalLogRepository approvalLogRepository;
  private final AgentCheckpointRepository checkpointRepository;
  private final OrderRepository orderRepository;

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
            .body(new AgentResponse("ERROR", List.of(), "小彦暂时遇到了一点技术问题，请稍后再试~", false))));
  }

  /**
   * 查询指定订单的会话状态，用于前端订单切换时判断断点续传情况。
   *
   * <p>返回订单是否存在活跃的 Checkpoint（即之前聊过天）、
   * 当前订单状态、以及是否存在待审批记录。</p>
   *
   * @param orderId 订单 ID
   * @return 订单会话状态信息
   */
  @GetMapping("/order-status")
  public Mono<ResponseEntity<java.util.Map<String, Object>>> getOrderStatus(
      @org.springframework.web.bind.annotation.RequestParam Long orderId) {
    return Mono.fromCallable(() -> {
          log.info("【API】查询订单状态，orderId={}", orderId);
          java.util.Map<String, Object> result = new java.util.HashMap<>();
          result.put("orderId", orderId);

          // 查询订单是否存在
          orderRepository.findById(orderId).ifPresentOrElse(order -> {
                result.put("orderStatus", order.getStatus());
                result.put("userId", order.getUserId());
              },
              () -> {
                result.put("orderStatus", "NOT_FOUND");
                result.put("userId", "");
              });

          // 检查是否有活跃的 Checkpoint（之前聊过天）
          String threadId = "order-" + orderId;
          boolean hasCheckpoint = checkpointRepository.existsById(threadId);
          result.put("hasCheckpoint", hasCheckpoint);

          // 查询是否有待审批记录
          String approvalStatus = approvalLogRepository.findByOrderId(orderId).stream()
              .findFirst()
              .map(ApprovalLog::getStatus)
              .orElse("NONE");
          result.put("approvalStatus", approvalStatus);

          return ResponseEntity.ok(result);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.error("【API】查询订单状态失败", e))
        .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().body(
            java.util.Map.<String, Object>of("error", "系统繁忙，请稍后再试"))));
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
   * 买家 SSE 订阅端点——实时接收审批结果推送。
   *
   * <p>买家前端在连接订单后建立 SSE 长连接。当主管审批结案时，
   * 后端通过 {@link UserSessionManager} 向对应 orderId 的通道推送事件。
   * 前端通过 {@code EventSource} 监听 {@code approval-result} 事件即可。</p>
   *
   * <p>事件格式：</p>
   * <pre>
   * event: approval-result
   * data: {"type":"REFUND_SUCCESS","orderId":1003,"message":"主管已批准退款，资金已原路退回。"}
   * </pre>
   *
   * @param orderId 订单 ID
   * @return SSE 事件流
   */
  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<String> subscribeEvents(@RequestParam Long orderId) {
    log.info("【SSE】买家订阅订单 {} 的实时事件", orderId);
    return userSessionManager.createFlux(orderId);
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
        .onErrorResume(e -> {
          // 幂等拦截等业务异常返回 409 Conflict，区分于系统错误
          if (e instanceof IllegalStateException) {
            log.warn("【API】审批业务拦截: {}", e.getMessage());
            return Mono.just(ResponseEntity.status(409)
                .body(new AgentResponse("REJECTED", List.of(), "操作被拒绝，请勿重复提交", false)));
          }
          log.error("【API】审批处理失败", e);
          return Mono.just(ResponseEntity.badRequest()
              .body(new AgentResponse("ERROR", List.of(), "系统繁忙，请稍后再试", false)));
        });
  }
}
