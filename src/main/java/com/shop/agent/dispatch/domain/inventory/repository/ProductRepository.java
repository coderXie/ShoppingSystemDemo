package com.shop.agent.dispatch.domain.inventory.repository;

import com.shop.agent.dispatch.domain.inventory.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 商品/库存数据访问层，提供商品实体的基础 CRUD 操作。
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
}
