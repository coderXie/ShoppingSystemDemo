package com.shop.agent.dispatch.domain.agent.repository;

import com.shop.agent.dispatch.domain.agent.entity.ApprovalLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * AI 退款审批日志数据访问层，支持按订单 ID 查询审批历史。
 */
@Repository
public interface ApprovalLogRepository extends JpaRepository<ApprovalLog, Long> {

  /**
   * 根据订单 ID 查询该订单相关的所有审批日志。
   *
   * @param orderId 订单号
   * @return 审批日志列表
   */
  List<ApprovalLog> findByOrderId(Long orderId);

  /**
   * 根据审批状态查询日志列表。
   *
   * @param status 审批状态
   * @return 审批日志列表
   */
  List<ApprovalLog> findByStatus(String status);

  /**
   * 查询指定订单是否存在指定状态的审批记录（用于幂等检查）。
   */
  boolean existsByOrderIdAndStatus(Long orderId, String status);

  /**
   * 根据状态列表查询审批日志（用于历史结案订单查询）。
   *
   * @param statuses 状态列表
   * @return 审批日志列表
   */
  List<ApprovalLog> findByStatusIn(List<String> statuses);
}
