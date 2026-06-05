package com.shop.agent.dispatch.domain.agent.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * 用户会话管理器，维护每个 orderId 对应的 SSE 推送通道。
 *
 * <p>买家在 /chat 页面连接后，通过 {@link #createFlux(Long)} 注册一个
 * {@link FluxSink}。当主管审批结案后，通过 {@link #send(Long, String, String)}
 * 向对应的买家通道推送实时事件。</p>
 *
 * <h3>事件格式：</h3>
 * <pre>
 * event: approval-result
 * data: {"type":"REFUND_SUCCESS","orderId":1003,"message":"主管已批准退款"}
 * </pre>
 */
@Slf4j
@Component
public class UserSessionManager {

  /** orderId → FluxSink（WebFlux SSE 通道） */
  private final Map<Long, FluxSink<String>> sinks = new ConcurrentHashMap<>();

  /**
   * 为指定订单创建 Flux 流（SSE 通道）。
   *
   * <p>返回一个无限流，通过 {@link FluxSink} 推送事件。
   * 当连接断开时自动清理。如果该 orderId 已有旧连接，会先关闭旧的。</p>
   */
  public Flux<String> createFlux(Long orderId) {
    return Flux.create(sink -> {
      // 关闭旧 sink
      FluxSink<String> old = sinks.remove(orderId);
      if (old != null) {
        log.info("【SSE】关闭订单 {} 的旧连接，创建新连接", orderId);
      }

      sinks.put(orderId, sink);
      sink.onDispose(() -> {
        sinks.remove(orderId);
        log.info("【SSE】订单 {} 的连接已断开，当前活跃连接数: {}", orderId, sinks.size());
      });

      log.info("【SSE】已为订单 {} 创建推送通道，当前活跃连接数: {}", orderId, sinks.size());
    }, FluxSink.OverflowStrategy.LATEST);
  }

  /**
   * 向指定订单的 SSE 通道推送事件。
   *
   * @param orderId  目标订单 ID
   * @param eventName 事件名称（如 "approval-result"，仅用于日志）
   * @param data      事件数据（JSON 字符串）
   */
  public void send(Long orderId, String eventName, String data) {
    FluxSink<String> sink = sinks.get(orderId);
    if (sink != null) {
      try {
        sink.next(data);
        log.info("【SSE】已向订单 {} 推送事件: {} → {}", orderId, eventName, data);
      } catch (Exception e) {
        log.warn("【SSE】推送失败，orderId={}, 移除连接: {}", orderId, e.getMessage());
        sinks.remove(orderId);
      }
    } else {
      log.debug("【SSE】订单 {} 无活跃连接，跳过推送", orderId);
    }
  }

  /**
   * 检查指定订单是否有活跃的 SSE 连接。
   */
  public boolean hasConnection(Long orderId) {
    return sinks.containsKey(orderId);
  }

  /**
   * 获取当前活跃的 SSE 连接数。
   */
  public int getActiveConnectionCount() {
    return sinks.size();
  }
}
