package com.shop.agent.dispatch.domain.logistics.service;

import com.shop.agent.dispatch.domain.logistics.entity.Logistics;
import com.shop.agent.dispatch.domain.logistics.repository.LogisticsRepository;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 物流领域服务，负责跨境物流轨迹查询，并将能力暴露为 AI 可调用的 Tool。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsService {

  private final LogisticsRepository logisticsRepository;

  /**
   * 根据订单号查询物流信息。
   *
   * @param orderId 订单号
   * @return 物流实体，包含最新位置与物流单状态
   */
  @Tool("根据订单号查询当前的跨境物流轨迹、最新位置以及物流单状态")
  public Logistics getLogisticsInfo(Long orderId) {
    return logisticsRepository.findByOrderId(orderId).orElse(null);
  }
}
