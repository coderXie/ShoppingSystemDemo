package com.shop.agent.dispatch.controller;

import com.shop.agent.dispatch.domain.agent.service.AgentSessionService;
import com.shop.agent.dispatch.dto.AgentResponse;
import com.shop.agent.dispatch.dto.ApproveRequest;
import com.shop.agent.dispatch.dto.ChatRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
          log.info("【API】收到聊天请求，orderId={}", request.orderId());
          return agentSessionService.chat(request.orderId(), request.userId(), request.message());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .map(ResponseEntity::ok)
        .doOnError(e -> log.error("【API】聊天处理失败", e))
        .onErrorResume(e -> Mono.just(ResponseEntity.badRequest()
            .body(new AgentResponse("ERROR", List.of(), e.getMessage(), false))));
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
              request.orderId(), request.decision());
          return agentSessionService.approve(request.orderId(), request.decision(), request.comment());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .map(ResponseEntity::ok)
        .doOnError(e -> log.error("【API】审批处理失败", e))
        .onErrorResume(e -> Mono.just(ResponseEntity.badRequest()
            .body(new AgentResponse("ERROR", List.of(), e.getMessage(), false))));
  }
}
