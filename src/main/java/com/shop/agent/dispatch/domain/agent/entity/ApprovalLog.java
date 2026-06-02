package com.shop.agent.dispatch.domain.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 退款审批日志实体，记录 AI Agent 提交的退款申请及人工管理员审核结果。
 *
 * <p>状态取值说明：
 * <ul>
 *   <li>{@code PENDING} — 待审核</li>
 *   <li>{@code APPROVED} — 已通过</li>
 *   <li>{@code REJECTED} — 已驳回</li>
 * </ul>
 */
@Entity
@Table(name = "approval_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id", nullable = false)
  private Long orderId;

  @Column(name = "agent_reason", nullable = false, length = 2048)
  private String agentReason;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "manager_comment", length = 2048)
  private String managerComment;
}
