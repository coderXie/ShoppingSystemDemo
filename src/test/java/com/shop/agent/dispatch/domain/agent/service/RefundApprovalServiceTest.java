package com.shop.agent.dispatch.domain.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shop.agent.dispatch.domain.agent.entity.ApprovalLog;
import com.shop.agent.dispatch.domain.agent.repository.ApprovalLogRepository;
import com.shop.agent.dispatch.domain.order.entity.Order;
import com.shop.agent.dispatch.domain.order.repository.OrderRepository;
import java.util.List;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shop.agent.dispatch.shoppingsystemdemo.ShoppingSystemDemoApplication;

/**
 * 退款审批服务单元测试。
 *
 * <p>验证三种核心状态流转：
 * REFUND_PENDING → APPROVED / REJECTED / REFUNDED</p>
 */
@SpringBootTest(classes = ShoppingSystemDemoApplication.class)
@ActiveProfiles("test")
@Transactional
class RefundApprovalServiceTest {

  @Autowired
  private RefundApprovalService refundApprovalService;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private ApprovalLogRepository approvalLogRepository;

  @org.springframework.boot.test.mock.mockito.MockBean
  private dev.langchain4j.model.chat.ChatLanguageModel chatLanguageModel;

  private Long orderId;

  @BeforeEach
  void setUp() {
    // 初始化一个状态为 REFUND_PENDING 的订单
    Order order = Order.builder()
        .userId("u123")
        .totalAmount(299.0)
        .status("REFUND_PENDING")
        .createTime(LocalDateTime.now())
        .build();
    order = orderRepository.save(order);
    this.orderId = order.getId();

    // 初始化对应的审批日志
    ApprovalLog log = ApprovalLog.builder()
        .orderId(orderId)
        .agentReason("海外仓缺货，无法补发")
        .status("PENDING")
        .build();
    approvalLogRepository.save(log);
  }

  @Test
  @DisplayName("审批通过：订单状态应从 REFUND_PENDING 变为 APPROVED")
  void approveRefund_shouldUpdateStatusToApproved() {
    // when
    refundApprovalService.approveRefund(orderId);

    // then
    Order updatedOrder = orderRepository.findById(orderId).orElseThrow();
    assertThat(updatedOrder.getStatus()).isEqualTo("APPROVED");

    ApprovalLog updatedLog = approvalLogRepository.findByOrderId(orderId).get(0);
    assertThat(updatedLog.getStatus()).isEqualTo("APPROVED");
  }

  @Test
  @DisplayName("审批驳回：订单状态应从 REFUND_PENDING 变为 REJECTED")
  void rejectRefund_shouldUpdateStatusToRejected() {
    // when
    refundApprovalService.rejectRefund(orderId, "库存已补货，不同意退款");

    // then
    Order updatedOrder = orderRepository.findById(orderId).orElseThrow();
    assertThat(updatedOrder.getStatus()).isEqualTo("REJECTED");

    ApprovalLog updatedLog = approvalLogRepository.findByOrderId(orderId).get(0);
    assertThat(updatedLog.getStatus()).isEqualTo("REJECTED");
    assertThat(updatedLog.getManagerComment()).isEqualTo("库存已补货，不同意退款");
  }

  @Test
  @DisplayName("最终退款：订单状态应从 APPROVED 变为 REFUNDED")
  void executeFinalRefund_shouldUpdateStatusToRefunded() {
    // given
    refundApprovalService.approveRefund(orderId);

    // when
    boolean result = refundApprovalService.executeFinalRefund(orderId);

    // then
    assertThat(result).isTrue();
    Order updatedOrder = orderRepository.findById(orderId).orElseThrow();
    assertThat(updatedOrder.getStatus()).isEqualTo("REFUNDED");
  }

  @Test
  @DisplayName("非 REFUND_PENDING 状态调用 approveRefund 应抛出异常")
  void approveRefund_whenStatusNotRefundPending_shouldThrow() {
    // given
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.setStatus("SHIPPED");
    orderRepository.save(order);

    // when & then
    assertThatThrownBy(() -> refundApprovalService.approveRefund(orderId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("订单状态不符合审批条件");
  }

  @Test
  @DisplayName("非 APPROVED 状态调用 executeFinalRefund 应返回 false")
  void executeFinalRefund_whenStatusNotApproved_shouldReturnFalse() {
    // given: 状态仍为 REFUND_PENDING（未经过 approveRefund）

    // when
    boolean result = refundApprovalService.executeFinalRefund(orderId);

    // then
    assertThat(result).isFalse();
  }
}
