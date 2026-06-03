package com.shop.agent.dispatch.domain.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shop.agent.dispatch.domain.logistics.entity.Logistics;
import com.shop.agent.dispatch.domain.logistics.repository.LogisticsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shop.agent.dispatch.shoppingsystemdemo.ShoppingSystemDemoApplication;

/**
 * 物流服务单元测试。
 */
@SpringBootTest(classes = ShoppingSystemDemoApplication.class)
@ActiveProfiles("test")
@Transactional
class LogisticsServiceTest {

  @Autowired
  private LogisticsService logisticsService;

  @Autowired
  private LogisticsRepository logisticsRepository;

  @org.springframework.boot.test.mock.mockito.MockBean
  private dev.langchain4j.model.chat.ChatLanguageModel chatLanguageModel;

  private Long orderId;

  @BeforeEach
  void setUp() {
    Logistics logistics = Logistics.builder()
        .orderId(1001L)
        .trackingNumber("SF1234567890")
        .status("IN_TRANSIT")
        .lastLocation("深圳宝安国际机场")
        .build();
    logistics = logisticsRepository.save(logistics);
    this.orderId = logistics.getOrderId();
  }

  @Test
  @DisplayName("根据订单号查询应返回正确的物流信息")
  void getLogisticsInfo_whenExists_shouldReturnLogistics() {
    Logistics result = logisticsService.getLogisticsInfo(orderId);

    assertThat(result).isNotNull();
    assertThat(result.getOrderId()).isEqualTo(orderId);
    assertThat(result.getTrackingNumber()).isEqualTo("SF1234567890");
    assertThat(result.getStatus()).isEqualTo("IN_TRANSIT");
    assertThat(result.getLastLocation()).isEqualTo("深圳宝安国际机场");
  }

  @Test
  @DisplayName("查询不存在的物流信息应抛出 IllegalArgumentException")
  void getLogisticsInfo_whenNotFound_shouldThrowException() {
    assertThatThrownBy(() -> logisticsService.getLogisticsInfo(99999L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("物流信息不存在");
  }
}
