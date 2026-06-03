package com.shop.agent.dispatch.domain.agent.service;

import com.shop.agent.dispatch.domain.agent.graph.OrderGraphBuilder;
import com.shop.agent.dispatch.domain.agent.state.OrderFlowState;
import com.shop.agent.dispatch.dto.AgentResponse;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
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

    // 获取当前状态（从 Checkpoint 或新建）
    OrderFlowState state = loadOrInitState(graph, config, orderId, userId);

    // 追加用户新消息
    state.getMessages().add(new UserMessage(message));
    updateGraphState(graph, config, state);

    // 执行图（从上次的 next node 继续）
    List<String> executedNodes = new ArrayList<>();
    OrderFlowState finalState = state;
    try {
      var stream = graph.stream(GraphInput.noArgs(), config);
      for (NodeOutput<OrderFlowState> output : stream) {
        log.info("【Chat】节点 {} 执行完成，threadId={}", output.node(), threadId);
        executedNodes.add(output.node());
        finalState = output.state();
      }
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
    String threadId = buildThreadId(orderId);
    RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
    CompiledGraph<OrderFlowState> graph = graphBuilder.getCompiledGraph();

    // 从数据库 Checkpoint 加载当前状态
    var snapshot = graph.getState(config);
    if (snapshot == null) {
      throw new IllegalArgumentException("找不到该订单的会话状态，可能从未启动或已过期，orderId=" + orderId);
    }

    OrderFlowState state = snapshot.state();

    // 写入管理员审批结果
    state.getContextData().put("managerDecision", decision);
    state.getContextData().put("managerComment", comment);
    state.setRequireHumanApproval(false);
    updateGraphState(graph, config, state);

    // 恢复执行（resume 表示从 interrupt 断点继续）
    List<String> executedNodes = new ArrayList<>();
    OrderFlowState finalState = state;
    try {
      var stream = graph.stream(GraphInput.resume(), config);
      for (NodeOutput<OrderFlowState> output : stream) {
        log.info("【Approve】恢复后节点 {} 执行完成，threadId={}", output.node(), threadId);
        executedNodes.add(output.node());
        finalState = output.state();
      }
    } catch (Exception e) {
      log.error("【Approve】图恢复执行异常，threadId={}", threadId, e);
      throw new RuntimeException("审批后执行失败: " + e.getMessage(), e);
    }

    return buildResponse(finalState, executedNodes);
  }

  private String buildThreadId(Long orderId) {
    return "order-" + orderId;
  }

  private OrderFlowState loadOrInitState(CompiledGraph<OrderFlowState> graph,
      RunnableConfig config, Long orderId, String userId) {
    try {
      var snapshot = graph.getState(config);
      if (snapshot != null) {
        log.debug("【Chat】从 Checkpoint 恢复状态，threadId={}", config.threadId().orElse("unknown"));
        return snapshot.state();
      }
    } catch (IllegalStateException e) {
      if (e.getMessage() != null && e.getMessage().contains("Missing Checkpoint")) {
        log.warn("【Chat】找不到 Checkpoint，创建新状态，orderId={}", orderId);
      } else {
        throw e;
      }
    }
    log.debug("【Chat】新建状态，orderId={}", orderId);
    return OrderFlowState.init(orderId, userId);
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
    String latestAiMessage = state.getMessages().stream()
        .filter(m -> m instanceof dev.langchain4j.data.message.AiMessage)
        .reduce((first, second) -> second)
        .map(ChatMessage::text)
        .orElse("");

    String status = state.isRequireHumanApproval()
        ? "INTERRUPTED"
        : "COMPLETED".equals(state.getCurrentDepartment()) || "END".equals(state.getCurrentDepartment())
            ? "COMPLETED"
            : "RUNNING";

    return new AgentResponse(status, executedNodes, latestAiMessage, state.isRequireHumanApproval());
  }
}
