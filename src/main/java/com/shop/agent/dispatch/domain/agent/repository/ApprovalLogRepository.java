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
}
