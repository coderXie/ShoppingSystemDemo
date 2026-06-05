package com.shop.agent.dispatch.domain.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.agent.dispatch.domain.agent.entity.AgentCheckpoint;
import com.shop.agent.dispatch.domain.agent.entity.ApprovalLog;
import com.shop.agent.dispatch.domain.agent.exception.AgentMaxIterationException;
import com.shop.agent.dispatch.domain.agent.graph.OrderGraphBuilder;
import com.shop.agent.dispatch.domain.agent.repository.AgentCheckpointRepository;
import com.shop.agent.dispatch.domain.agent.repository.ApprovalLogRepository;
import com.shop.agent.dispatch.domain.agent.state.OrderFlowState;
import com.shop.agent.dispatch.dto.AgentResponse;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Service;

/**
 * Agent 会话服务，封装 LangGraph 工作流的启动、继续与恢复逻辑。
 *
 * <p>负责管理 {@link RunnableConfig}（threadId）、图状态的加载/保存，
 * 以及 Human-in-the-Loop 中断后的恢复。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSessionService {

  private final OrderGraphBuilder graphBuilder;
  private final AgentCheckpointRepository checkpointRepository;
  private final ApprovalLogRepository approvalLogRepository;
  private final RefundApprovalService refundApprovalService;
  private final com.shop.agent.dispatch.domain.agent.event.UserSessionManager userSessionManager;
  private final ObjectMapper objectMapper;

  /**
   * 用户发送消息，启动新会话或继续已有会话。
   *
   * @param orderId 订单 ID
   * @param userId  用户 ID
   * @param message 用户输入内容
   * @return 执行结果
   */
  public AgentResponse chat(Long orderId, String userId, String message) {
    String threadId = buildThreadId(orderId);
    RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
    CompiledGraph<OrderFlowState> graph = graphBuilder.getCompiledGraph();

    boolean hasCheckpoint = checkpointRepository.existsById(threadId);

    if (!hasCheckpoint) {
      // 新会话：先创建初始 Checkpoint 存入数据库，包含用户首条消息。
      // graph.stream(noArgs, config) 会从数据库加载 Checkpoint 作为初始状态，
      // 避免调用 initialStateFromSchema() 导致的 NPE。
      saveInitialCheckpoint(threadId, orderId, userId, message);
      log.info("【Chat】已创建初始 Checkpoint，threadId={}", threadId);
    } else {
      // 已有会话：从 Checkpoint 恢复状态，追加新消息后更新。
      OrderFlowState state = loadExistingState(graph, config);
      List<ChatMessage> messages = new ArrayList<>(state.getMessages());
      messages.add(new UserMessage(message));
      state.setMessages(messages);
      // 重置熔断计数器——每次新用户消息进来，视为新一轮对话
      state.setToolCallCount(0);
      updateGraphState(graph, config, state);
    }

    // 执行图（从 entry point 或上次中断的 next node 继续）
    List<String> executedNodes = new ArrayList<>();
    OrderFlowState finalState = null;
    try {
      var stream = graph.stream(GraphInput.noArgs(), config);
      for (NodeOutput<OrderFlowState> output : stream) {
        log.info("【Chat】节点 {} 执行完成，threadId={}", output.node(), threadId);
        executedNodes.add(output.node());
        finalState = output.state();
      }
    } catch (AgentMaxIterationException e) {
      // ===== 熔断降级：捕获幻觉死循环异常，返回平稳兜底响应 =====
      log.warn("【Chat】触发大模型幻觉熔断，orderId={}, 当前迭代={}/{}, threadId={}",
          e.getOrderId(), e.getCurrentCount(), e.getMaxLoops(), threadId);
      executedNodes.add("fallbackNode");
      return new AgentResponse(
          "COMPLETED",
          executedNodes,
          "很抱歉，小彦当前处理该订单遇到了一点技术问题，已自动为您呼叫后台人工客服跟进，请稍后。",
          true  // 标记需要人工介入
      );
    } catch (Exception e) {
      log.error("【Chat】图执行异常，threadId={}", threadId, e);
      throw new RuntimeException("处理用户消息失败: " + e.getMessage(), e);
    }

    return buildResponse(finalState, executedNodes);
  }

  /**
   * 管理员审批，恢复中断的图执行。
   *
   * @param orderId 订单 ID
   * @param decision 审批结果（APPROVED / REJECTED）
   * @param comment 审批意见
   * @return 执行结果
   */
  public AgentResponse approve(Long orderId, String decision, String comment) {
    // ===== 幂等校验：检查订单当前审批状态，防止重复审批 =====
    String currentApprovalStatus = approvalLogRepository.findByOrderId(orderId).stream()
        .findFirst()
        .map(ApprovalLog::getStatus)
        .orElse("NONE");

    if (!"PENDING".equals(currentApprovalStatus)) {
      String hint = switch (currentApprovalStatus) {
        case "APPROVED" -> "该订单已审批通过，请勿重复提交";
        case "REJECTED" -> "该订单已审批驳回，请勿重复提交";
        default -> "该订单无待审批记录，无法审批";
      };
      log.warn("【Approve】幂等拦截，orderId={}, currentStatus={}, decision={}",
          orderId, currentApprovalStatus, decision);
      throw new IllegalStateException(hint);
    }

    String threadId = buildThreadId(orderId);
    RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
    CompiledGraph<OrderFlowState> graph = graphBuilder.getCompiledGraph();

    // 加载图状态（可能从 checkpoint 恢复，也可能自动初始化）
    org.bsc.langgraph4j.state.StateSnapshot<OrderFlowState> snapshot = loadCheckpointWithFallback(
        graph, config, orderId);

    OrderFlowState state = snapshot.state();
    log.info("【Approve】加载状态完成, orderId={}, currentDepartment={}, requireHumanApproval={}",
        orderId, state.getCurrentDepartment(), state.isRequireHumanApproval());

    // ============================================================
    // 直接执行审批业务逻辑（不依赖图恢复，因为图可能已经到达 END）
    // ============================================================
    List<ChatMessage> messages = new ArrayList<>(state.getMessages());
    String resultMessage;

    if ("APPROVED".equals(decision)) {
      log.info("【Approve】执行审批通过流程, orderId={}", orderId);
      boolean refundOk = refundApprovalService.approveAndExecuteRefund(orderId, comment);
      log.info("【Approve】退款执行结果: orderId={}, success={}", orderId, refundOk);

      resultMessage = "【审批结果】订单 #" + orderId
          + " 已由主管审批通过，系统已执行退款，订单状态已更新为：已退款(REFUNDED)，库存已回滚。工作流已结束。";
    } else {
      log.info("【Approve】执行驳回流程, orderId={}", orderId);
      refundApprovalService.rejectRefundAndRestoreOrder(orderId, comment);

      resultMessage = "【审批结果】订单 #" + orderId
          + " 退款申请已被主管驳回，订单状态已恢复为：已发货(SHIPPED)。工作流已结束。";
    }

    // 追加审批结果消息
    messages.add(new dev.langchain4j.data.message.AiMessage(resultMessage));
    state.setMessages(messages);

    // 更新图状态为终态
    state.setCurrentDepartment("FINISH");
    state.setRequireHumanApproval(false);
    state.getContextData().put("managerDecision", decision);
    state.getContextData().put("managerComment", comment);
    updateGraphState(graph, config, state);

    log.info("【Approve】审批完成, orderId={}, decision={}, resultMessage={}", orderId, decision, resultMessage);

    // ===== SSE 实时推送：通知买家前端审批结果 =====
    pushApprovalEvent(orderId, decision);

    return buildResponse(state, List.of("supervisorNode"));
  }

  /**
   * 通过 SSE 向买家前端推送审批结果事件。
   *
   * <p>当主管审批结案后，自动查找该 orderId 的活跃 SSE 连接并推送。
   * 买家聊天窗口收到事件后自动弹出系统提示，无需手动刷新。</p>
   */
  private void pushApprovalEvent(Long orderId, String decision) {
    try {
      String eventType = "APPROVED".equals(decision) ? "REFUND_SUCCESS" : "REFUND_REJECTED";
      String message = "APPROVED".equals(decision)
          ? "主管已批准您的退款，资金已原路退回。"
          : "主管已驳回您的退款申请，订单恢复正常状态。";

      String payload = String.format(
          "{\"type\":\"%s\",\"orderId\":%d,\"message\":\"%s\"}",
          eventType, orderId, message);

      userSessionManager.send(orderId, "approval-result", payload);
      log.info("【SSE】已推送审批结果事件，orderId={}, type={}", orderId, eventType);
    } catch (Exception e) {
      // SSE 推送失败不影响审批主流程
      log.warn("【SSE】推送审批结果失败，orderId={}: {}", orderId, e.getMessage());
    }
  }

  private String buildThreadId(Long orderId) {
    return "order-" + orderId;
  }

  /**
   * 为审批流程创建 Checkpoint（checkpoint 丢失时的兜底）。
   *
   * <p>将图状态设置为 supervisorNode 中断点，
   * 使后续 graph.stream(resume) 能从 supervisorNode 恢复执行。</p>
   */
  private void saveApprovalCheckpoint(String threadId, Long orderId) {
    try {
      Map<String, Object> stateMap = new HashMap<>();
      stateMap.put("orderId", orderId);
      stateMap.put("userId", "system");
      stateMap.put("messages", new ArrayList<java.util.Map<String, Object>>());
      stateMap.put("currentDepartment", "SUPERVISOR");
      stateMap.put("contextData", new HashMap<String, Object>());
      stateMap.put("requireHumanApproval", true);
      stateMap.put("toolCallCount", 0);

      saveCheckpointJson(threadId, "approval-" + System.currentTimeMillis(),
          stateMap, "supervisorNode", "supervisorNode");
      log.info("【Approve】已创建审批 Checkpoint，threadId={}", threadId);
    } catch (Exception e) {
      log.error("【Approve】创建审批 Checkpoint 失败，threadId={}", threadId, e);
      throw new RuntimeException("创建审批会话失败", e);
    }
  }

  /**
   * 为新会话创建初始 Checkpoint 并持久化到数据库。
   *
   * <p>Checkpoint 中包含完整的初始状态（含用户首条消息），
   * {@code nextNodeId} 设为 {@code customerServiceNode}（图的入口节点），
   * 使 {@code graph.stream(noArgs, config)} 能从该节点开始执行。</p>
   */
  private void saveInitialCheckpoint(String threadId, Long orderId, String userId, String message) {
    try {
      // 构造初始状态，messages 以 List<Map> 格式存储（Java 序列化安全）
      Map<String, Object> stateMap = new HashMap<>();
      stateMap.put("orderId", orderId);
      stateMap.put("userId", userId);
      List<Map<String, Object>> messagesList = new ArrayList<>();
      Map<String, Object> userMsg = new HashMap<>();
      userMsg.put("@class", "dev.langchain4j.data.message.UserMessage");
      userMsg.put("type", "USER");
      userMsg.put("text", message);
      messagesList.add(userMsg);
      stateMap.put("messages", messagesList);
      stateMap.put("currentDepartment", "CUSTOMER_SERVICE");
      stateMap.put("contextData", new HashMap<String, Object>());
      stateMap.put("requireHumanApproval", false);
      stateMap.put("toolCallCount", 0);

      saveCheckpointJson(threadId, "init-" + System.currentTimeMillis(),
          stateMap, "__START__", "customerServiceNode");
    } catch (Exception e) {
      log.error("【Chat】创建初始 Checkpoint 失败，threadId={}", threadId, e);
      throw new RuntimeException("创建初始会话失败", e);
    }
  }

  /**
   * 将 checkpoint 数据序列化为 JSON 并持久化到数据库。
   *
   * <p>统一使用 {@link ObjectMapper} 序列化，确保 state 中的 ChatMessage 对象
   * 由 {@link com.shop.agent.dispatch.domain.agent.config.ChatMessageSerializer}
   * 自动写入 {@code @class} 类型标记。</p>
   */
  private void saveCheckpointJson(String threadId, String checkpointId,
      Map<String, Object> stateMap, String nodeId, String nextNodeId) throws Exception {
    Map<String, Object> checkpoint = new HashMap<>();
    checkpoint.put("id", checkpointId);
    checkpoint.put("state", stateMap);
    checkpoint.put("nodeId", nodeId);
    checkpoint.put("nextNodeId", nextNodeId);

    String json = objectMapper.writeValueAsString(List.of(checkpoint));

    AgentCheckpoint entity = AgentCheckpoint.builder()
        .threadId(threadId)
        .checkpointJson(json)
        .updateTime(LocalDateTime.now())
        .build();
    checkpointRepository.save(entity);
  }

  /**
   * 从已有 Checkpoint 加载状态（用于已有会话恢复）。
   */
  private OrderFlowState loadExistingState(CompiledGraph<OrderFlowState> graph,
      RunnableConfig config) {
    var snapshot = graph.getState(config);
    if (snapshot != null) {
      return snapshot.state();
    }
    throw new IllegalStateException("预期存在 Checkpoint 但未找到");
  }

  /**
   * 带兜底机制的 Checkpoint 加载：先尝试从图中加载状态，失败时自动初始化后重试。
   *
   * <p>解决直接操作数据库与 AbstractCheckpointSaver 缓存不一致导致的
   * {@code Missing Checkpoint} 问题。</p>
   */
  private org.bsc.langgraph4j.state.StateSnapshot<OrderFlowState> loadCheckpointWithFallback(
      CompiledGraph<OrderFlowState> graph, RunnableConfig config, Long orderId) {
    // 第一次尝试：直接加载
    var snapshot = safeGetState(graph, config);
    if (snapshot != null) {
      return snapshot;
    }

    log.warn("【Approve】Checkpoint 不存在或缓存未命中，尝试自动初始化，orderId={}", orderId);
    saveApprovalCheckpoint(config.threadId().orElse(buildThreadId(orderId)), orderId);

    // 第二次尝试：初始化后重新加载
    snapshot = safeGetState(graph, config);
    if (snapshot != null) {
      log.info("【Approve】初始化后成功加载 Checkpoint，orderId={}", orderId);
      return snapshot;
    }

    log.error("【Approve】初始化后仍无法加载 Checkpoint，orderId={}", orderId);
    throw new IllegalArgumentException(
        "找不到该订单的会话状态，可能从未启动或已过期，orderId=" + orderId);
  }

  /**
   * 安全调用 graph.getState()，捕获 Missing Checkpoint 异常返回 null。
   */
  private org.bsc.langgraph4j.state.StateSnapshot<OrderFlowState> safeGetState(
      CompiledGraph<OrderFlowState> graph, RunnableConfig config) {
    try {
      return graph.getState(config);
    } catch (IllegalStateException e) {
      if (e.getMessage() != null && e.getMessage().contains("Missing Checkpoint")) {
        return null;
      }
      throw e;
    }
  }

  /**
   * 安全地从 Checkpoint 加载图状态快照。
   *
   * <p>若 Checkpoint 不存在（订单从未启动聊天、已过期或已被清理），
   * 抛出带有明确业务含义的 {@link IllegalArgumentException}，
   * 而非底层 {@link IllegalStateException: Missing Checkpoint}。</p>
   */
  private org.bsc.langgraph4j.state.StateSnapshot<OrderFlowState> loadCheckpoint(
      CompiledGraph<OrderFlowState> graph, RunnableConfig config, Long orderId) {
    try {
      var snapshot = graph.getState(config);
      if (snapshot == null) {
        throw new IllegalArgumentException(
            "找不到该订单的会话状态，可能从未启动或已过期，orderId=" + orderId);
      }
      return snapshot;
    } catch (IllegalStateException e) {
      if (e.getMessage() != null && e.getMessage().contains("Missing Checkpoint")) {
        log.warn("【Approve】找不到 Checkpoint，orderId={}", orderId);
        throw new IllegalArgumentException(
            "找不到该订单的会话状态，可能从未启动或已过期，orderId=" + orderId, e);
      }
      throw e;
    }
  }

  private void updateGraphState(CompiledGraph<OrderFlowState> graph,
      RunnableConfig config, OrderFlowState state) {
    try {
      graph.updateState(config, state.data());
    } catch (Exception e) {
      log.error("更新图状态失败，threadId={}", config.threadId().orElse("unknown"), e);
      throw new RuntimeException("状态更新失败", e);
    }
  }

  private AgentResponse buildResponse(OrderFlowState state, List<String> executedNodes) {
    if (state == null) {
      return new AgentResponse("ERROR", executedNodes, "图执行未产生最终状态", false);
    }

    String latestAiMessage = state.getMessages().stream()
        .filter(m -> m instanceof dev.langchain4j.data.message.AiMessage)
        .reduce((first, second) -> second)
        .map(ChatMessage::text)
        .orElse("");

    String status = state.isRequireHumanApproval()
        ? "INTERRUPTED"
        : "COMPLETED".equals(state.getCurrentDepartment())
            || "END".equals(state.getCurrentDepartment())
            || "FINISH".equals(state.getCurrentDepartment())
            ? "COMPLETED"
            : "RUNNING";

    return new AgentResponse(status, executedNodes, latestAiMessage, state.isRequireHumanApproval());
  }
}
