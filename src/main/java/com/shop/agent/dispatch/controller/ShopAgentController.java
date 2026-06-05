package com.shop.agent.dispatch.controller;

import com.shop.agent.dispatch.domain.agent.entity.ApprovalLog;
import com.shop.agent.dispatch.domain.agent.repository.ApprovalLogRepository;
import com.shop.agent.dispatch.dto.ApprovalLogVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 前台管理后台专用控制器，提供历史审批日志查询等管理接口。
 *
 * <p>与 {@link AgentController}（用户侧聊天 + 审批操作）职责分离，
 * 本控制器专注于管理后台的只读查询场景。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ShopAgentController {

  private final ApprovalLogRepository approvalLogRepository;

  /**
   * 查询历史结案审批列表。
   *
   * <p>支持按审批结果类型筛选：
   * <ul>
   *   <li>{@code ALL} — 查询所有已结案记录（APPROVED + REJECTED）</li>
   *   <li>{@code APPROVED} — 仅查询审批通过的记录</li>
   *   <li>{@code REJECTED} — 仅查询审批驳回的记录</li>
   * </ul>
   *
   * @param type 筛选类型，默认 {@code ALL}
   * @return 审批日志 VO 列表
   */
  @GetMapping("/approvals")
  public Mono<ResponseEntity<List<ApprovalLogVO>>> getApprovals(
      @RequestParam(value = "type", defaultValue = "ALL") String type) {

    return Mono.fromCallable(() -> {
          log.info("【管理后台】查询历史审批列表，type={}", type);

          List<ApprovalLog> entities = switch (type.toUpperCase()) {
            case "APPROVED" -> approvalLogRepository.findByStatus("APPROVED");
            case "REJECTED" -> approvalLogRepository.findByStatus("REJECTED");
            default -> approvalLogRepository.findByStatusIn(List.of("APPROVED", "REJECTED"));
          };

          List<ApprovalLogVO> voList = entities.stream()
              .map(ApprovalLogVO::fromEntity)
              .toList();

          log.info("【管理后台】查询到 {} 条历史审批记录", voList.size());
          return ResponseEntity.ok(voList);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.error("【管理后台】查询历史审批列表失败", e))
        .onErrorResume(e -> Mono.just(ResponseEntity.ok(List.of())));
  }
}
