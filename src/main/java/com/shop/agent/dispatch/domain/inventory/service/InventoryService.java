package com.shop.agent.dispatch.domain.inventory.service;

import com.shop.agent.dispatch.domain.inventory.entity.Product;
import com.shop.agent.dispatch.domain.inventory.repository.ProductRepository;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 库存领域服务，负责海外仓库存检查，并将能力暴露为 AI 可调用的 Tool。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

  private final ProductRepository productRepository;

  /**
   * 检查特定商品的海外仓库存是否充足。
   *
   * @param productId 商品 ID
   * @param quantity  需要的数量
   * @return true 表示库存充足；false 表示库存不足或商品不存在
   */
  @Tool("检查特定商品的海外仓库存是否充足")
  public boolean checkProductStock(Long productId, int quantity) {
    Product product = productRepository.findById(productId).orElse(null);
    if (product == null) {
      log.warn("商品不存在，productId={}", productId);
      return false;
    }
    boolean sufficient = product.getStockCount() >= quantity;
    log.info("库存检查，productId={}, 需要={}, 实际={}, 结果={}",
        productId, quantity, product.getStockCount(), sufficient);
    return sufficient;
  }
}
