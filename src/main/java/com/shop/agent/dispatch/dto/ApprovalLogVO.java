package com.shop.agent.dispatch.dto;

import com.shop.agent.dispatch.domain.agent.entity.ApprovalLog;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 审批日志视图对象（VO），面向前端管理后台展示。
 *
 * <p>与领域实体 {@link ApprovalLog} 解耦，仅暴露前端所需字段，
 * 符合 DDD 防腐层规范。</p>
 */
@Getter
@Builder
@AllArgsConstructor
public class ApprovalLogVO {

  /** 审批记录主键 */
  private final Long id;

  /** 关联订单 ID */
  private final Long orderId;

  /** AI Agent 提交的退款理由 / 异常分析报告 */
  private final String agentReason;

  /** 审批状态：APPROVED / REJECTED */
  private final String status;

  /** 主管审批批注（可为空） */
  private final String managerComment;

  /** 结案时间 */
  private final LocalDateTime createTime;

  /**
   * 将领域实体转换为 VO。
   *
   * @param entity 审批日志实体
   * @return VO 对象
   */
  public static ApprovalLogVO fromEntity(ApprovalLog entity) {
    return ApprovalLogVO.builder()
        .id(entity.getId())
        .orderId(entity.getOrderId())
        .agentReason(entity.getAgentReason())
        .status(entity.getStatus())
        .managerComment(entity.getManagerComment())
        .createTime(entity.getCreateTime())
        .build();
  }
}
