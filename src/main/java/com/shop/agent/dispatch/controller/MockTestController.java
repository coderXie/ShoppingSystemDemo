package com.shop.agent.dispatch.controller;

import com.shop.agent.dispatch.domain.agent.service.MockTestService;
import com.shop.agent.dispatch.dto.MockOrderRequest;
import java.util.List;
import java.util.Map;
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
 * 测试造数控制器——快捷注入模拟订单数据，供手动测试和 Pytest 自动化使用。
 *
 * <p>仅在 {@code dev} profile 下激活，生产环境不会注册此控制器。</p>
 *
 * <h3>接口列表：</h3>
 * <ul>
 *   <li>{@code POST /api/test/mock-order} — 注入指定场景的测试订单</li>
 * </ul>
 *
 * <h3>使用示例（curl）：</h3>
 * <pre>
 * # 注入缺货审批流场景
 * curl -X POST http://localhost:8080/api/test/mock-order \
 *   -H "Content-Type: application/json" \
 *   -d '{"orderId": 9901, "sceneType": "OUT_OF_STOCK"}'
 *
 * # 注入正常物流场景
 * curl -X POST http://localhost:8080/api/test/mock-order \
 *   -H "Content-Type: application/json" \
 *   -d '{"orderId": 9902, "sceneType": "NORMAL"}'
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class MockTestController {

  private final MockTestService mockTestService;

  /**
   * 注入测试订单数据。
   *
   * <p>在同一事务中同时操作业务表（订单/物流/库存）和 Agent 状态表（Checkpoint/审批日志），
   * 确保数据一致性。</p>
   *
   * <h3>场景说明：</h3>
   * <ul>
   *   <li>{@code OUT_OF_STOCK} — 海外仓爆仓缺货：库存归零 + 审批日志 PENDING +
   *       Checkpoint 挂起在 SUPERVISOR 节点。前端聊天框切换到该订单时直接看到"退款审批中"。</li>
   *   <li>{@code NORMAL} — 正常物流：充足库存 + 正常物流轨迹。
   *       可用于测试 AI 客服直接闭环处理（查物流/查订单）。</li>
   * </ul>
   *
   * @param request 包含 orderId 和 sceneType
   * @return 200 OK + 成功信息
   */
  @PostMapping("/mock-order")
  public Mono<ResponseEntity<Map<String, Object>>> mockOrder(@RequestBody MockOrderRequest request) {
    return Mono.fromCallable(() -> {
          // 参数校验
          if (request.getOrderId() == null) {
            return ResponseEntity.badRequest()
                .body(Map.<String, Object>of("error", "orderId 不能为空"));
          }
          String scene = request.getSceneType();
          if (scene == null || !List.of("OUT_OF_STOCK", "NORMAL").contains(scene)) {
            return ResponseEntity.badRequest()
                .body(Map.<String, Object>of("error",
                    "sceneType 必须为 OUT_OF_STOCK 或 NORMAL，当前值: " + scene));
          }

          log.info("【MockTest】收到造数请求，orderId={}, scene={}", request.getOrderId(), scene);

          // 执行造数，返回数据库自动生成的实际 orderId
          Long actualOrderId = mockTestService.injectMockOrder(request.getOrderId(), scene);

          String message = String.format(
              "成功注入 [场景: %s] 的测试订单数据，可直接在管理后台/聊天框联调！", scene);

          return ResponseEntity.ok(Map.<String, Object>of(
              "status", "OK",
              "orderId", actualOrderId,
              "sceneType", scene,
              "message", message
          ));
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(e -> {
          log.error("【MockTest】造数失败", e);
          return Mono.just(ResponseEntity.internalServerError()
              .body(Map.<String, Object>of("error", "造数失败: " + e.getMessage())));
        });
  }

  /**
   * 清理所有测试造数产生的脏数据（审批日志 + checkpoint 中引用了不存在的订单的记录）。
   */
  @PostMapping("/cleanup")
  public Mono<ResponseEntity<Map<String, Object>>> cleanup() {
    return Mono.fromCallable(() -> {
          int cleaned = mockTestService.cleanupOrphanData();
          return ResponseEntity.ok(Map.<String, Object>of(
              "status", "OK",
              "cleanedRecords", cleaned,
              "message", "已清理 " + cleaned + " 条孤儿审批记录"
          ));
        })
        .subscribeOn(Schedulers.boundedElastic());
  }
}
