package com.shop.agent.dispatch.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单实体，表示跨境电商系统中的用户订单。
 *
 * <p>状态取值说明：
 * <ul>
 *   <li>{@code PENDING_PAY} — 待支付</li>
 *   <li>{@code SHIPPED} — 已发货</li>
 *   <li>{@code REFUND_PENDING} — 退款审批中（AI 已提交，等待主管审核）</li>
 *   <li>{@code APPROVED} — 已同意（主管审批通过）</li>
 *   <li>{@code REJECTED} — 不同意（主管审批驳回）</li>
 *   <li>{@code REFUNDED} — 已退款（系统最终执行完成）</li>
 * </ul>
 */
@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, length = 64)
  private String userId;

  @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal totalAmount;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "create_time", nullable = false, updatable = false)
  private LocalDateTime createTime;
}
