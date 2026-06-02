package com.shop.agent.dispatch.domain.logistics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 物流轨迹实体，记录跨境订单的物流状态与位置信息。
 *
 * <p>状态取值说明：
 * <ul>
 *   <li>{@code IN_TRANSIT} — 运输中</li>
 *   <li>{@code DELIVERED} — 已签收</li>
 * </ul>
 */
@Entity
@Table(name = "logistics")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Logistics {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id", nullable = false)
  private Long orderId;

  @Column(name = "tracking_number", nullable = false, length = 128)
  private String trackingNumber;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "last_location", length = 512)
  private String lastLocation;
}
