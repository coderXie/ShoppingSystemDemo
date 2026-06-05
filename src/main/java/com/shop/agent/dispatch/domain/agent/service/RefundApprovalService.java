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
 * 退款审批领域服务，处理 AI 提交的退款申请、主管审批以及最终退款执行。
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
    // ===== 幂等检查：防止 AI 重复调用导致多条 PENDING 审批记录 =====
    if (approvalLogRepository.existsByOrderIdAndStatus(orderId, "PENDING")) {
      log.warn("【幂等】订单 {} 已存在待审批记录，跳过重复提交", orderId);
      return;
    }

    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("订单不存在，orderId=" + orderId));

    order.setStatus("REFUND_PENDING");
    orderRepository.save(order);

    ApprovalLog approvalLog = ApprovalLog.builder()
        .orderId(orderId)
        .agentReason(reason)
        .status("PENDING")
        .createTime(java.time.LocalDateTime.now())
        .build();
    approvalLogRepository.save(approvalLog);

    log.info("AI 已提交退款审批申请，orderId={}, reason={}", orderId, reason);
  }

  /**
   * 主管审批通过：将订单状态修改为已同意(APPROVED)。
   *
   * @param orderId 订单号
   */
  @Tool("主管审批通过后，调用此方法将订单状态修改为已同意(APPROVED)，并更新审批日志状态和批注")
  @Transactional
  public void approveRefund(Long orderId, String comment) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("订单不存在，orderId=" + orderId));

    if (!"REFUND_PENDING".equals(order.getStatus())) {
      log.warn("订单状态不是退款审批中，无法审批，orderId={}, currentStatus={}",
          orderId, order.getStatus());
      throw new IllegalStateException("订单状态不符合审批条件: " + order.getStatus());
    }

    order.setStatus("APPROVED");
    orderRepository.save(order);

    approvalLogRepository.findByOrderId(orderId).stream()
        .findFirst()
        .ifPresent(approvalLog -> {
          approvalLog.setStatus("APPROVED");
          approvalLog.setManagerComment(comment);
          approvalLogRepository.save(approvalLog);
        });

    log.info("主管已审批通过，订单状态已更新为 APPROVED，orderId={}, comment={}", orderId, comment);
  }

  /**
   * 主管审批驳回：将订单状态修改为不同意(REJECTED)。
   *
   * @param orderId 订单号
   * @param comment 主管驳回批注
   */
  @Tool("主管审批驳回后，调用此方法将订单状态修改为不同意(REJECTED)，并记录驳回原因到审批日志")
  @Transactional
  public void rejectRefund(Long orderId, String comment) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("订单不存在，orderId=" + orderId));

    if (!"REFUND_PENDING".equals(order.getStatus())) {
      log.warn("订单状态不是退款审批中，无法驳回，orderId={}, currentStatus={}",
          orderId, order.getStatus());
      throw new IllegalStateException("订单状态不符合驳回条件: " + order.getStatus());
    }

    order.setStatus("REJECTED");
    orderRepository.save(order);

    approvalLogRepository.findByOrderId(orderId).stream()
        .findFirst()
        .ifPresent(approvalLog -> {
          approvalLog.setStatus("REJECTED");
          approvalLog.setManagerComment(comment);
          approvalLogRepository.save(approvalLog);
        });

    log.info("主管已驳回退款申请，订单状态已更新为 REJECTED，orderId={}", orderId);
  }

  /**
   * 主管驳回退款后，将订单状态恢复为已发货(SHIPPED)。
   *
   * <p>先调用 {@link #rejectRefund} 更新审批日志，
   * 再将订单状态从 REJECTED 改回 SHIPPED，使订单回到正常流转状态。</p>
   *
   * @param orderId 订单号
   * @param comment 主管驳回批注
   */
  @Transactional
  public void rejectRefundAndRestoreOrder(Long orderId, String comment) {
    rejectRefund(orderId, comment);

    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("订单不存在，orderId=" + orderId));
    order.setStatus("SHIPPED");
    orderRepository.save(order);
    log.info("主管驳回后订单已恢复为 SHIPPED，orderId={}", orderId);
  }

  /**
   * 主管审批通过并立即执行退款（在同一事务中）。
   *
   * <p>将 {@link #approveRefund} 与 {@link #executeFinalRefund} 合并到同一事务，
   * 避免前者成功提交后后者失败导致订单卡在 APPROVED 状态。</p>
   *
   * @param orderId 订单号
   * @param comment 主管批注
   * @return true 表示退款执行成功
   */
  @Transactional
  public boolean approveAndExecuteRefund(Long orderId, String comment) {
    approveRefund(orderId, comment);
    return executeFinalRefund(orderId);
  }

  /**
   * 系统最终执行退款（在已同意(APPROVED)后调用）。
   *
   * <p>将订单状态修改为已退款(REFUNDED)，并把原订单绑定的商品库存回滚加回数据库。</p>
   *
   * @param orderId 订单号
   * @return true 表示退款执行成功
   */
  @Tool("在订单状态为已同意(APPROVED)后，由系统最终执行退款。调用此方法将订单状态修改为已退款(REFUNDED)，并回滚商品库存")
  @Transactional
  public boolean executeFinalRefund(Long orderId) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("订单不存在，orderId=" + orderId));

    if (!"APPROVED".equals(order.getStatus())) {
      log.warn("订单状态不是已同意，无法执行退款，orderId={}, currentStatus={}",
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

    log.info("退款已最终执行完成，orderId={}", orderId);
    return true;
  }
}
