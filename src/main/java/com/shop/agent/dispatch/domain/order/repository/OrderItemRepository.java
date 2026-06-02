package com.shop.agent.dispatch.domain.order.repository;

import com.shop.agent.dispatch.domain.order.entity.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 订单明细数据访问层，支持按订单 ID 查询关联的商品明细列表。
 */
@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

  /**
   * 根据订单 ID 查询该订单下所有的商品明细。
   *
   * @param orderId 订单号
   * @return 订单明细列表
   */
  List<OrderItem> findByOrderId(Long orderId);
}
