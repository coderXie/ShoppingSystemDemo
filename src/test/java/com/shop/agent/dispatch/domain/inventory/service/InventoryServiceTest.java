package com.shop.agent.dispatch.domain.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shop.agent.dispatch.domain.inventory.entity.Product;
import com.shop.agent.dispatch.domain.inventory.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shop.agent.dispatch.shoppingsystemdemo.ShoppingSystemDemoApplication;

/**
 * 库存服务单元测试。
 */
@SpringBootTest(classes = ShoppingSystemDemoApplication.class)
@ActiveProfiles("test")
@Transactional
class InventoryServiceTest {

  @Autowired
  private InventoryService inventoryService;

  @Autowired
  private ProductRepository productRepository;

  @org.springframework.boot.test.mock.mockito.MockBean
  private dev.langchain4j.model.chat.ChatLanguageModel chatLanguageModel;

  private Long productId;

  @BeforeEach
  void setUp() {
    Product product = Product.builder()
        .name("iPhone 15 Pro")
        .price(new java.math.BigDecimal("999.00"))
        .stockCount(100)
        .build();
    product = productRepository.save(product);
    this.productId = product.getId();
  }

  @Test
  @DisplayName("库存充足时应返回 true")
  void checkProductStock_whenSufficient_shouldReturnTrue() {
    boolean result = inventoryService.checkProductStock(productId, 50);
    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("库存不足时应返回 false")
  void checkProductStock_whenInsufficient_shouldReturnFalse() {
    boolean result = inventoryService.checkProductStock(productId, 200);
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("商品不存在时应返回 false")
  void checkProductStock_whenProductNotFound_shouldReturnFalse() {
    boolean result = inventoryService.checkProductStock(99999L, 1);
    assertThat(result).isFalse();
  }
}
