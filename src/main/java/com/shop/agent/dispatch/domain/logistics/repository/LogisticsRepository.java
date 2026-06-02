package com.shop.agent.dispatch.domain.logistics.repository;

import com.shop.agent.dispatch.domain.logistics.entity.Logistics;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 物流数据访问层，支持按订单 ID 查询对应的物流轨迹记录。
 */
@Repository
public interface LogisticsRepository extends JpaRepository<Logistics, Long> {

  /**
   * 根据订单 ID 查询物流信息。
   *
   * @param orderId 订单号
   * @return 物流记录
   */
  Optional<Logistics> findByOrderId(Long orderId);
}
