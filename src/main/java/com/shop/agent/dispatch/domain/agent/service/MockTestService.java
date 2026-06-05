package com.shop.agent.dispatch.domain.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.agent.dispatch.domain.agent.entity.AgentCheckpoint;
import com.shop.agent.dispatch.domain.agent.entity.ApprovalLog;
import com.shop.agent.dispatch.domain.agent.repository.AgentCheckpointRepository;
import com.shop.agent.dispatch.domain.agent.repository.ApprovalLogRepository;
import com.shop.agent.dispatch.domain.inventory.entity.Product;
import com.shop.agent.dispatch.domain.inventory.repository.ProductRepository;
import com.shop.agent.dispatch.domain.order.entity.OrderItem;
import com.shop.agent.dispatch.domain.order.repository.OrderItemRepository;
import com.shop.agent.dispatch.domain.logistics.entity.Logistics;
import com.shop.agent.dispatch.domain.logistics.repository.LogisticsRepository;
import com.shop.agent.dispatch.domain.order.entity.Order;
import com.shop.agent.dispatch.domain.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试造数服务——向数据库注入模拟订单数据，供手动测试和自动化测试使用。
 *
 * <p>仅在 {@code dev} profile 下激活，生产环境不会注册此 Bean。</p>
 *
 * <h3>支持的场景：</h3>
 * <ul>
 *   <li>{@code OUT_OF_STOCK} — 海外仓爆仓缺货：库存归零 + 审批日志 + Checkpoint 挂起在主管节点</li>
 *   <li>{@code NORMAL} — 正常物流：充足库存 + 正常物流轨迹</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockTestService {

  private final OrderRepository orderRepository;
  private final OrderItemRepository orderItemRepository;
  private final LogisticsRepository logisticsRepository;
  private final ProductRepository productRepository;
  private final AgentCheckpointRepository checkpointRepository;
  private final ApprovalLogRepository approvalLogRepository;
  private final ObjectMapper objectMapper;

  // ==================== 物流位置池 ====================
  private static final String[] LOCATIONS_IN_TRANSIT = {
      "美国洛杉矶海关，清关异常滞留",
      "韩国仁川国际机场，等待转运",
      "日本东京成田机场，海关查验中",
      "德国法兰克福海关，清关延迟",
      "澳大利亚悉尼港，等待卸货",
      "新加坡樟宜机场，中转排队中",
      "英国伦敦希思罗机场，海关扣留待检",
      "加拿大温哥华港，码头拥堵延误",
      "马来西亚吉隆坡，转运仓分拣中",
      "泰国曼谷素万那普机场，航班延误等待起飞"
  };

  private static final String[] LOCATIONS_DELIVERED = {
      "上海浦东新区，已签收",
      "北京朝阳区，已送达",
      "深圳南山区，快递柜已签收",
      "杭州西湖区，已签收",
      "广州天河区，代收点已签收"
  };

  private static final String[] TRACKING_PREFIXES = {"SF", "YT", "ZT", "JD", "EMS", "STO", "YD", "极兔"};

  // ==================== 商品池 ====================
  private static final String[] PRODUCT_NAMES = {
      "跨境蓝牙耳机 Pro",
      "日韩代购保温杯",
      "欧美限定球鞋",
      "海外版机械键盘",
      "进口猫粮 10kg 装",
      "澳洲保健品套装",
      "日本限定手办",
      "德国进口厨具三件套",
      "美版 Switch 游戏卡带",
      "韩国面膜 50 片礼盒"
  };

  private static final BigDecimal[] PRODUCT_PRICES = {
      new BigDecimal("199.00"), new BigDecimal("299.00"), new BigDecimal("599.00"),
      new BigDecimal("899.00"), new BigDecimal("1299.00"), new BigDecimal("459.00"),
      new BigDecimal("769.00"), new BigDecimal("159.00"), new BigDecimal("2099.00"),
      new BigDecimal("349.00")
  };

  // ==================== 退款原因池 ====================
  private static final String[] REFUND_REASONS = {
      "【供应链异常】海外仓 SKU-%d 库存为 0，供应商反馈该型号已停产，无法补货。建议批准退款并下架该商品。",
      "【物流丢件】跨境包裹在 %s 处滞留超过 15 天，物流商确认丢件，无法找回。建议全额退款。",
      "【质量问题】买家收到商品后反馈严重质量问题（破损/无法使用），海外仓无同款库存可换货。建议退款。",
      "【海关扣关】商品在目的国海关被扣留，申报材料不合规，清关失败退回。建议退款并重新申报。",
      "【供应商断供】该 SKU 供应商已停止合作，海外仓库存为 0 且无替代品可推荐。建议批准退款。",
      "【物流超时】包裹在中转仓滞留超过 20 天，物流商反馈无法继续派送。建议退款并发起物流理赔。",
      "【错发商品】海外仓拣货错误，买家收到的商品与订单不符，且正确商品已无库存。建议退款。",
      "【价格异常】商品定价系统异常导致售价远低于成本价，订单无法正常履约。建议退款并修正价格。"
  };

  private static final String[] AI_SUMMARIES = {
      "经核查，海外仓该 SKU 彻底缺货，供应商反馈已停产，无法补货且无替代品。已提交退款审批，等待主管审核。",
      "物流轨迹显示包裹在 %s 滞留超过 15 天，物流商确认丢件。建议全额退款并向物流商发起理赔。",
      "买家反馈商品严重损坏，海外仓无同款可换。已拍照留证并提交退款申请，请主管审核。",
      "商品在目的国海关被扣留，清关失败。已联系货代确认无法重新申报，建议退款。",
      "供应商已停止合作且库存归零，系统内无替代商品推荐。已提交退款审批，请主管批示。"
  };

  private static final String[] USER_MESSAGES = {
      "我要退款，商品一直没到",
      "都快一个月了快递还没到，能退款吗",
      "收到的东西是坏的，我要退货退款",
      "包裹显示清关失败，怎么回事",
      "物流信息半个月没更新了，帮我退款吧",
      "我买的东西和收到的不一样，退款",
      "客服说没货了，帮我退款",
      "这个订单我不想等了，直接退款"
  };

  /**
   * 清理孤儿数据：审批日志和订单明细中引用了不存在的订单的记录。
   */
  @Transactional
  public int cleanupOrphanData() {
    List<ApprovalLog> allLogs = approvalLogRepository.findAll();
    int cleaned = 0;
    for (ApprovalLog log : allLogs) {
      if (!orderRepository.existsById(log.getOrderId())) {
        checkpointRepository.findById("order-" + log.getOrderId())
            .ifPresent(checkpointRepository::delete);
        // 清理该订单的孤儿明细
        orderItemRepository.findByOrderId(log.getOrderId()).forEach(orderItemRepository::delete);
        approvalLogRepository.delete(log);
        cleaned++;
      }
    }
    log.info("【造数】清理孤儿数据完成，共清理 {} 条", cleaned);
    return cleaned;
  }

  /**
   * 注入测试订单数据（整个操作在同一事务中完成）。
   *
   * @param orderId   目标订单 ID
   * @param sceneType 场景类型
   */
  @Transactional
  public Long injectMockOrder(Long orderId, String sceneType) {
    log.info("【造数】开始注入测试数据，orderId={}, scene={}", orderId, sceneType);

    cleanupOrphanData();

    // 随机选一个商品和价格
    int productIdx = ThreadLocalRandom.current().nextInt(PRODUCT_NAMES.length);
    String productName = PRODUCT_NAMES[productIdx];
    BigDecimal price = PRODUCT_PRICES[productIdx];
    int quantity = ThreadLocalRandom.current().nextInt(1, 4);
    BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));

    // ① 订单表
    Order order = Order.builder()
        .userId("mock_user_" + orderId)
        .totalAmount(totalAmount)
        .status("SHIPPED")
        .createTime(LocalDateTime.now().minusDays(ThreadLocalRandom.current().nextInt(2, 10)))
        .build();
    order = orderRepository.save(order);
    Long actualOrderId = order.getId();
    log.info("【造数】订单已插入，actualOrderId={}, status=SHIPPED, amount={}", actualOrderId, totalAmount);

    // ② 物流表：NORMAL 场景随机在途/已送达，OUT_OF_STOCK 场景固定在途
    String location;
    String logisticsStatus;
    if ("NORMAL".equals(sceneType) && ThreadLocalRandom.current().nextBoolean()) {
      location = pickRandom(LOCATIONS_DELIVERED);
      logisticsStatus = "DELIVERED";
    } else {
      location = pickRandom(LOCATIONS_IN_TRANSIT);
      logisticsStatus = "IN_TRANSIT";
    }
    String trackingPrefix = pickRandom(TRACKING_PREFIXES);
    Logistics logistics = Logistics.builder()
        .orderId(actualOrderId)
        .trackingNumber(trackingPrefix + actualOrderId + String.format("%03d", ThreadLocalRandom.current().nextInt(100, 999)))
        .status(logisticsStatus)
        .lastLocation(location)
        .build();
    logisticsRepository.save(logistics);
    log.info("【造数】物流记录已插入，orderId={}, status={}, location={}", actualOrderId, logisticsStatus, location);

    // ③ 库存与商品表
    Long productId = injectProducts(actualOrderId, productName, price, quantity, sceneType);

    // ④ Agent 状态机快照 + 审批日志（仅 OUT_OF_STOCK 场景）
    if ("OUT_OF_STOCK".equals(sceneType)) {
      injectOutOfStockState(actualOrderId, location);
    }

    log.info("【造数】完成！orderId={}, scene={} 数据已全部注入", actualOrderId, sceneType);
    return actualOrderId;
  }

  /**
   * 根据场景类型注入商品/库存数据，并创建订单明细关联。
   */
  private Long injectProducts(Long orderId, String productName, BigDecimal price,
      int quantity, String sceneType) {
    Product product = Product.builder()
        .name(productName + " #" + orderId)
        .price(price)
        .stockCount("OUT_OF_STOCK".equals(sceneType) ? 0 : ThreadLocalRandom.current().nextInt(50, 500))
        .build();
    product = productRepository.save(product);
    log.info("【造数】商品已插入，productId={}, name={}, stock={}", product.getId(), product.getName(), product.getStockCount());

    // 创建订单明细：关联订单与商品
    OrderItem orderItem = OrderItem.builder()
        .orderId(orderId)
        .productId(product.getId())
        .quantity(quantity)
        .unitPrice(price)
        .build();
    orderItemRepository.save(orderItem);
    log.info("【造数】订单明细已插入，orderId={}, productId={}, quantity={}", orderId, product.getId(), quantity);

    return product.getId();
  }

  /**
   * 注入缺货场景的 Agent 状态：审批日志 + Checkpoint 挂起在主管节点。
   */
  private void injectOutOfStockState(Long orderId, String location) {
    // 幂等清理
    approvalLogRepository.findByOrderId(orderId).forEach(approvalLogRepository::delete);
    checkpointRepository.findById("order-" + orderId).ifPresent(checkpointRepository::delete);

    // 随机退款原因
    String reasonTemplate = pickRandom(REFUND_REASONS);
    String reason = formatReason(reasonTemplate, orderId, location);

    ApprovalLog approvalLog = ApprovalLog.builder()
        .orderId(orderId)
        .agentReason(reason)
        .status("PENDING")
        .createTime(LocalDateTime.now().minusHours(ThreadLocalRandom.current().nextInt(1, 12)))
        .build();
    approvalLogRepository.save(approvalLog);
    log.info("【造数】审批日志已插入，orderId={}, reason={}", orderId, reason);

    // 订单状态改为退款审批中
    orderRepository.findById(orderId).ifPresent(o -> {
      o.setStatus("REFUND_PENDING");
      orderRepository.save(o);
    });

    // 随机 AI 摘要
    String aiSummaryTemplate = pickRandom(AI_SUMMARIES);
    String aiSummary = formatReason(aiSummaryTemplate, orderId, location);

    injectCheckpoint(orderId, "SUPERVISOR", true, aiSummary);
  }

  /**
   * 向 agent_checkpoints 表写入一个模拟的图状态快照。
   */
  private void injectCheckpoint(Long orderId, String department,
      boolean requireHumanApproval, String aiMessage) {
    try {
      String threadId = "order-" + orderId;

      Map<String, Object> stateMap = new HashMap<>();
      stateMap.put("orderId", orderId);
      stateMap.put("userId", "mock_user_" + orderId);
      stateMap.put("currentDepartment", department);
      stateMap.put("requireHumanApproval", requireHumanApproval);
      stateMap.put("toolCallCount", ThreadLocalRandom.current().nextInt(2, 6));
      stateMap.put("contextData", new HashMap<String, Object>());

      String userMsg = pickRandom(USER_MESSAGES);
      List<Map<String, Object>> messages = new ArrayList<>();
      messages.add(buildMessageMap("dev.langchain4j.data.message.UserMessage", "USER", userMsg));
      messages.add(buildMessageMap("dev.langchain4j.data.message.AiMessage", "AI", aiMessage));
      stateMap.put("messages", messages);

      Map<String, Object> checkpoint = new HashMap<>();
      checkpoint.put("id", "mock-cp-" + System.currentTimeMillis());
      checkpoint.put("state", stateMap);
      checkpoint.put("nodeId", "supervisorNode");
      checkpoint.put("nextNodeId", "supervisorNode");

      String json = objectMapper.writeValueAsString(List.of(checkpoint));

      AgentCheckpoint cp = AgentCheckpoint.builder()
          .threadId(threadId)
          .checkpointJson(json)
          .updateTime(LocalDateTime.now())
          .build();
      checkpointRepository.save(cp);
      log.info("【造数】Checkpoint 已注入，threadId={}, department={}", threadId, department);

    } catch (Exception e) {
      log.error("【造数】Checkpoint 注入失败，orderId={}", orderId, e);
      throw new RuntimeException("Checkpoint 注入失败", e);
    }
  }

  /**
   * 构造消息 Map（兼容 ChatMessageSerializer 的 @class 格式）。
   */
  private Map<String, Object> buildMessageMap(String className, String type, String text) {
    Map<String, Object> map = new HashMap<>();
    map.put("@class", className);
    map.put("type", type);
    map.put("text", text);
    return map;
  }

  private static String pickRandom(String[] pool) {
    return pool[ThreadLocalRandom.current().nextInt(pool.length)];
  }

  /**
   * 格式化模板：按顺序替换 %d → orderId，%s → location。
   *
   * <p>不使用 {@code String.format}，避免模板中同时存在 %d 和 %s 时参数错位。</p>
   */
  private static String formatReason(String template, Long orderId, String location) {
    String result = template;
    int dIdx = result.indexOf("%d");
    if (dIdx >= 0) {
      result = result.substring(0, dIdx) + orderId + result.substring(dIdx + 2);
    }
    int sIdx = result.indexOf("%s");
    if (sIdx >= 0) {
      result = result.substring(0, sIdx) + location + result.substring(sIdx + 2);
    }
    return result;
  }
}
