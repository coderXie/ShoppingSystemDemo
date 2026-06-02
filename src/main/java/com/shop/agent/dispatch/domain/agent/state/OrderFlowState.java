package com.shop.agent.dispatch.domain.agent.state;

import dev.langchain4j.data.message.ChatMessage;
import org.bsc.langgraph4j.state.AgentState;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;

/**
 * LangGraph4j 全局状态类，承载智能客服+供应链异常协同调度流程中的完整上下文。
 *
 * <p>继承 {@link AgentState} 以适配 LangGraph4j 的状态管理机制。
 * 所有字段通过 {@link #data()} Map 存取，支持图的增量更新与 Channel Reducer 合并。</p>
 *
 * <p>实现 {@link Serializable} 以便后续持久化到数据库，支持流程的断点续传与故障恢复。</p>
 *
 * <p>{@code currentDepartment} 取值说明：
 * <ul>
 *   <li>{@code CUSTOMER_SERVICE} — 客服部门</li>
 *   <li>{@code INVENTORY} — 库存部门</li>
 *   <li>{@code LOGISTICS} — 物流部门</li>
 *   <li>{@code SUPERVISOR} — 主管/审批部门</li>
 *   <li>{@code END} — 流程结束</li>
 * </ul>
 */
@EqualsAndHashCode(callSuper = true)
public class OrderFlowState extends AgentState implements Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  public OrderFlowState(Map<String, Object> initData) {
    super(initData);
  }

  /**
   * 快速初始化方法，用于构造图的初始状态。
   *
   * @param orderId 订单 ID
   * @param userId  用户 ID
   * @return 初始状态实例
   */
  public static OrderFlowState init(Long orderId, String userId) {
    Map<String, Object> data = new HashMap<>();
    data.put("orderId", orderId);
    data.put("userId", userId);
    data.put("messages", new ArrayList<ChatMessage>());
    data.put("currentDepartment", "CUSTOMER_SERVICE");
    data.put("contextData", new HashMap<String, Object>());
    data.put("requireHumanApproval", false);
    return new OrderFlowState(data);
  }

  @SuppressWarnings("unchecked")
  public String getUserId() {
    return value("userId").map(String.class::cast).orElse(null);
  }

  public void setUserId(String userId) {
    data().put("userId", userId);
  }

  @SuppressWarnings("unchecked")
  public Long getOrderId() {
    return value("orderId").map(Long.class::cast).orElse(null);
  }

  public void setOrderId(Long orderId) {
    data().put("orderId", orderId);
  }

  @SuppressWarnings("unchecked")
  public List<ChatMessage> getMessages() {
    return (List<ChatMessage>) value("messages").orElse(new ArrayList<>());
  }

  public void setMessages(List<ChatMessage> messages) {
    data().put("messages", messages);
  }

  public String getCurrentDepartment() {
    return value("currentDepartment").map(String.class::cast).orElse("CUSTOMER_SERVICE");
  }

  public void setCurrentDepartment(String currentDepartment) {
    data().put("currentDepartment", currentDepartment);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> getContextData() {
    return (Map<String, Object>) value("contextData").orElse(new HashMap<>());
  }

  public void setContextData(Map<String, Object> contextData) {
    data().put("contextData", contextData);
  }

  public boolean isRequireHumanApproval() {
    return value("requireHumanApproval").map(Boolean.class::cast).orElse(false);
  }

  public void setRequireHumanApproval(boolean requireHumanApproval) {
    data().put("requireHumanApproval", requireHumanApproval);
  }
}
