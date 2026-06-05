package com.shop.agent.dispatch.controller;

import com.shop.agent.dispatch.domain.agent.entity.AgentCheckpoint;
import com.shop.agent.dispatch.domain.agent.repository.AgentCheckpointRepository;
import com.shop.agent.dispatch.domain.agent.repository.ApprovalLogRepository;
import com.shop.agent.dispatch.domain.order.repository.OrderRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 开发环境专用测试端点（仅 dev profile 激活）。
 *
 * <p>生产环境不会注册此控制器，避免测试接口暴露。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
@RequiredArgsConstructor
public class DevTestController {

  private final AgentCheckpointRepository checkpointRepository;
  private final ApprovalLogRepository approvalLogRepository;
  private final OrderRepository orderRepository;

  /**
   * 初始化测试 Checkpoint，用于绕过聊天步骤直接测试审批流程。
   */
  @GetMapping("/init-checkpoint")
  public Mono<ResponseEntity<String>> initCheckpoint() {
    return Mono.fromCallable(() -> {
          Long orderId = 1003L;
          String threadId = "order-" + orderId;
          String json = "[{\"id\":\"cp-1\",\"state\":{\"orderId\":" + orderId
              + ",\"userId\":\"user_1002\",\"messages\":[],\"currentDepartment\":\"SUPERVISOR\","
              + "\"contextData\":{},\"requireHumanApproval\":true,\"toolCallCount\":0},"
              + "\"nodeId\":\"supervisorNode\",\"nextNodeId\":\"supervisorNode\"}]";

          AgentCheckpoint cp = AgentCheckpoint.builder()
              .threadId(threadId)
              .checkpointJson(json)
              .updateTime(LocalDateTime.now())
              .build();
          checkpointRepository.save(cp);

          orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus("REFUND_PENDING");
            orderRepository.save(order);
          });
          approvalLogRepository.findByOrderId(orderId).stream().findFirst().ifPresent(approvalLog -> {
            approvalLog.setStatus("PENDING");
            approvalLog.setManagerComment(null);
            approvalLogRepository.save(approvalLog);
          });

          return "测试 Checkpoint 已初始化，orderId=" + orderId;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .map(ResponseEntity::ok)
        .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().body("初始化失败: " + e.getMessage())));
  }
}
