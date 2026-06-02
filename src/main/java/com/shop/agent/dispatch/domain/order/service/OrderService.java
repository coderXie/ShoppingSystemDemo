package com.shop.agent.dispatch.domain.order.service;

import com.shop.agent.dispatch.domain.order.entity.Order;
import com.shop.agent.dispatch.domain.order.repository.OrderRepository;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 订单领域服务，负责订单查询等核心业务逻辑，并将关键能力暴露为 AI 可调用的 Tool。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

  private final OrderRepository orderRepository;

  /**
   * 根据订单号查询订单详情。
   *
   * @param orderId 订单号
   * @return 订单实体，包含状态、总金额等信息
   */
  @Tool("根据订单号查询订单的详情，包含订单状态和总金额")
  public Order getOrderDetail(Long orderId) {
    return orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("订单不存在，orderId=" + orderId));
  }
}
