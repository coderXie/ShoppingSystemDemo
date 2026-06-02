package com.shop.agent.dispatch.domain.order.repository;

import com.shop.agent.dispatch.domain.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 订单数据访问层，提供订单实体的基础 CRUD 操作。
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
}
