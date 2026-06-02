package com.shop.agent.dispatch.domain.agent.service;

import com.shop.agent.dispatch.domain.agent.entity.ApprovalLog;
import com.shop.agent.dispatch.domain.agent.repository.ApprovalLogRepository;
import com.shop.agent.dispatch.domain.inventory.entity.Product;
import com.shop.agent.dispatch.domain.inventory.repository.ProductRepository;
import com.shop.agent.dispatch.domain.order.entity.Order;
import com.shop.agent.dispatch.domain.order.entity.OrderItem;
import com.shop.agent.dispatch.domain.order.repository.OrderItemRepository;
import com.shop.agent.dispatch.domain.order.repository.OrderRepository;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款审批领域服务，处理 AI 提交的退款申请以及最终退款执行。
 *
 * <p>将退款审批与库存回滚等关键操作暴露为 AI 可调用的 Tool，
 * 所有涉及数据库更新的方法均配有 {@link Transactional} 注解以保证事务一致性。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundApprovalService {

  private final OrderRepository orderRepository;
  private final ApprovalLogRepository approvalLogRepository;
  private final OrderItemRepository orderItemRepository;
  private final ProductRepository productRepository;

  /**
   * AI 提交退款审批申请。
   *
   * <p>当确认发生供应链异常（如海外仓缺货且无法补货）需要退款时调用。
   * 会将订单状态修改为 REFUND_PENDING，并将 AI 提交的异常分析报告写入审批日志表。</p>
   *
   * @param orderId 订单号
   * @param reason  AI 生成的异常分析报告/退款原因
   */
  @Tool("当确认发生供应链异常（如海外仓缺货且无法补货）需要退款时，调用此方法将订单状态修改为退款审批中(REFUND_PENDING)，并写入 AI 提交的异常分析报告到审批日志表")
  @Transactional
  public void submitRefundApproval(Long orderId, String reason) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("订单不存在，orderId=" + orderId));

    order.setStatus("REFUND_PENDING");
    orderRepository.save(order);

    ApprovalLog approvalLog = ApprovalLog.builder()
        .orderId(orderId)
        .agentReason(reason)
        .status("PENDING")
        .build();
    approvalLogRepository.save(approvalLog);

    log.info("AI 已提交退款审批申请，orderId={}, reason={}", orderId, reason);
  }

  /**
   * 系统最终执行退款。
   *
   * <p>在主管人工审批通过后调用。将订单状态修改为 REFUNDED，
   * 并把原订单绑定的商品库存按订单明细逐件回滚加回数据库。</p>
   *
   * @param orderId 订单号
   * @return true 表示退款执行成功；false 表示订单状态不符合退款条件
   */
  @Tool("在主管人工审批通过后，由系统最终执行退款。调用此方法将订单状态修改为已退款(REFUNDED)，并把原订单绑定的商品库存回滚加回数据库")
  @Transactional
  public boolean executeFinalRefund(Long orderId) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("订单不存在，orderId=" + orderId));

    if (!"REFUND_PENDING".equals(order.getStatus())) {
      log.warn("订单状态不是退款审批中，无法执行退款，orderId={}, currentStatus={}",
          orderId, order.getStatus());
      return false;
    }

    order.setStatus("REFUNDED");
    orderRepository.save(order);

    List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
    for (OrderItem item : items) {
      Product product = productRepository.findById(item.getProductId())
          .orElseThrow(() -> new IllegalArgumentException(
              "商品不存在，productId=" + item.getProductId()));
      product.setStockCount(product.getStockCount() + item.getQuantity());
      productRepository.save(product);
      log.info("库存已回滚，productId={}, rollbackQuantity={}, newStock={}",
          item.getProductId(), item.getQuantity(), product.getStockCount());
    }

    approvalLogRepository.findByOrderId(orderId).stream()
        .findFirst()
        .ifPresent(approvalLog -> {
          approvalLog.setStatus("APPROVED");
          approvalLogRepository.save(approvalLog);
        });

    log.info("退款已最终执行完成，orderId={}", orderId);
    return true;
  }
}
