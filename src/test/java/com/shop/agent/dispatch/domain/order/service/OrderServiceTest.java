package com.shop.agent.dispatch.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shop.agent.dispatch.domain.order.entity.Order;
import com.shop.agent.dispatch.domain.order.repository.OrderRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shop.agent.dispatch.shoppingsystemdemo.ShoppingSystemDemoApplication;

/**
 * 订单服务单元测试。
 */
@SpringBootTest(classes = ShoppingSystemDemoApplication.class)
@ActiveProfiles("test")
@Transactional
class OrderServiceTest {

  @Autowired
  private OrderService orderService;

  @Autowired
  private OrderRepository orderRepository;

  @org.springframework.boot.test.mock.mockito.MockBean
  private dev.langchain4j.model.chat.ChatLanguageModel chatLanguageModel;

  private Long orderId;

  @BeforeEach
  void setUp() {
    Order order = Order.builder()
        .userId("u1001")
        .totalAmount(new java.math.BigDecimal("1999.00"))
        .status("SHIPPED")
        .createTime(LocalDateTime.now())
        .build();
    order = orderRepository.save(order);
    this.orderId = order.getId();
  }

  @Test
  @DisplayName("根据订单号查询应返回正确的订单详情")
  void getOrderDetail_whenOrderExists_shouldReturnOrder() {
    Order result = orderService.getOrderDetail(orderId);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(orderId);
    assertThat(result.getUserId()).isEqualTo("u1001");
    assertThat(result.getTotalAmount()).isEqualByComparingTo(new java.math.BigDecimal("1999.00"));
    assertThat(result.getStatus()).isEqualTo("SHIPPED");
  }

  @Test
  @DisplayName("查询不存在的订单应抛出 IllegalArgumentException")
  void getOrderDetail_whenOrderNotFound_shouldThrowException() {
    assertThatThrownBy(() -> orderService.getOrderDetail(99999L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("订单不存在");
  }
}
