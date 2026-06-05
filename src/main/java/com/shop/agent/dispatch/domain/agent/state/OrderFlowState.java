package com.shop.agent.dispatch.domain.agent.state;

import dev.langchain4j.data.message.ChatMessage;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;
import org.bsc.langgraph4j.state.AgentState;

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

  /**
   * 对父类 {@link AgentState} 内部 {@code data} 字段的可变引用。
   *
   * <p>父类的 {@link AgentState#data()} 返回 {@code Collections.unmodifiableMap(this.data)}，
   * 导致所有 setter 都会抛 {@code UnsupportedOperationException}。
   * 通过反射获取内部 {@code data} 字段的直接引用，使 setter 能真正修改状态。</p>
   */
  private final Map<String, Object> internalData;

  public OrderFlowState(Map<String, Object> initData) {
    super(fixTypes(new HashMap<>(initData)));
    try {
      Field field = AgentState.class.getDeclaredField("data");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, Object> dataRef = (Map<String, Object>) field.get(this);
      this.internalData = dataRef;
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException("无法通过反射获取 AgentState 内部 data 字段", e);
    }
  }

  /**
   * 修复 Jackson 反序列化导致的类型丢失：Integer → Long。
   *
   * <p>注意：不再在此处还原 {@code List<Map>} → {@code List<ChatMessage>}，
   * 因为 {@code ChatMessage} 未实现 {@code Serializable}，会导致图的内部
   * checkpoint 机制（Java 序列化深拷贝）抛出 {@code NotSerializableException}。
   * 消息的还原改为在 {@link #getMessages()} 中按需进行。</p>
   */
  private static Map<String, Object> fixTypes(Map<String, Object> data) {
    if (data.get("orderId") instanceof Integer) {
      data.put("orderId", ((Integer) data.get("orderId")).longValue());
    }
    Object reqApproval = data.get("requireHumanApproval");
    if (reqApproval instanceof Integer) {
      data.put("requireHumanApproval", ((Integer) reqApproval) != 0);
    }
    // 熔断计数器类型修复：Jackson 反序列化可能将 Integer 变成 Long
    Object countObj = data.get("toolCallCount");
    if (countObj instanceof Long) {
      data.put("toolCallCount", ((Long) countObj).intValue());
    } else if (countObj == null) {
      data.put("toolCallCount", 0);
    }
    return data;
  }

  // ==================== 消息序列化策略 ====================
  // ChatMessage 未实现 Serializable，而 langgraph4j 的 checkpoint 机制使用
  // Java 序列化做深拷贝。因此 setMessages() 必须将 ChatMessage 转为 Map 后存储。
  //
  // 双重序列化路径：
  //   1. Java 序列化（langgraph4j 内部深拷贝）：Map<String,Object> ✅ Serializable
  //   2. Jackson 序列化（JdbcCheckpointSaver 写数据库）：Map 由 Jackson 默认序列化
  //      → 读取时 JdbcCheckpointSaver.restoreChatMessages() 用 ObjectMapper
  //        将 Map 二次转换为 ChatMessage（通过 ChatMessageDeserializer 还原 @class）

  /**
   * 消息类型安全转换工具——兼容 Map 格式（旧数据/手动 checkpoint）。
   */
  @SuppressWarnings("unchecked")
  private static List<ChatMessage> convertToChatMessages(Object raw) {
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      return new ArrayList<>();
    }
    List<ChatMessage> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof ChatMessage cm) {
        if (cm.text() != null && !cm.text().trim().isEmpty()) {
          result.add(cm);
        }
      } else if (item instanceof Map<?, ?> map) {
        // 兜底：手动 checkpoint 写入的 Map 格式
        ChatMessage cm = fromMap((Map<String, Object>) map);
        if (cm != null) {
          result.add(cm);
        }
      }
    }
    return result;
  }

  /**
   * 从 Map 格式还原 ChatMessage（兼容旧数据和手动 checkpoint）。
   */
  private static ChatMessage fromMap(Map<String, Object> map) {
    Object textObj = map.get("text");
    String text = textObj instanceof String ? (String) textObj : "";
    String className = map.get("@class") instanceof String ? (String) map.get("@class") : null;
    String type = map.get("type") instanceof String ? (String) map.get("type") : null;

    // ToolExecutionResultMessage 允许空 text
    boolean isToolResult = (className != null && className.contains("ToolExecutionResultMessage"))
        || "TOOL_RESULT".equalsIgnoreCase(type);
    if ((text == null || text.trim().isEmpty()) && !isToolResult) {
      return null;
    }
    if (text == null) text = "";

    try {
      // 判据 1：@class 精确匹配
      if (className != null) {
        if (className.contains("ToolExecutionResultMessage")) {
          String id = map.get("id") instanceof String ? (String) map.get("id") : "";
          String toolName = map.get("toolName") instanceof String ? (String) map.get("toolName") : "";
          return dev.langchain4j.data.message.ToolExecutionResultMessage.from(id, toolName, text);
        }
        if (className.contains("UserMessage")) return new dev.langchain4j.data.message.UserMessage(text);
        if (className.contains("AiMessage")) return new dev.langchain4j.data.message.AiMessage(text);
        if (className.contains("SystemMessage")) return new dev.langchain4j.data.message.SystemMessage(text);
      }
      // 判据 2：type 兜底
      if (type != null) {
        return switch (type.toUpperCase()) {
          case "USER" -> new dev.langchain4j.data.message.UserMessage(text);
          case "AI" -> new dev.langchain4j.data.message.AiMessage(text);
          case "SYSTEM" -> new dev.langchain4j.data.message.SystemMessage(text);
          case "TOOL_RESULT" -> {
            String id = map.get("id") instanceof String ? (String) map.get("id") : "";
            String toolName = map.get("toolName") instanceof String ? (String) map.get("toolName") : "";
            yield dev.langchain4j.data.message.ToolExecutionResultMessage.from(id, toolName, text);
          }
          default -> new dev.langchain4j.data.message.UserMessage(text);
        };
      }
      return new dev.langchain4j.data.message.UserMessage(text);
    } catch (IllegalArgumentException e) {
      return null;
    }
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
    data.put("toolCallCount", 0);
    return new OrderFlowState(data);
  }

  @SuppressWarnings("unchecked")
  public String getUserId() {
    return value("userId").map(String.class::cast).orElse(null);
  }

  public void setUserId(String userId) {
    internalData.put("userId", userId);
  }

  public Long getOrderId() {
    return value("orderId").map(v -> {
      if (v instanceof Number) {
        return ((Number) v).longValue();
      }
      return (Long) v;
    }).orElse(null);
  }

  public void setOrderId(Long orderId) {
    internalData.put("orderId", orderId);
  }

  /**
   * 获取消息列表。
   *
   * <p>自动处理两种存储格式：</p>
   * <ul>
   *   <li>{@code List<ChatMessage>} — 来自 JdbcCheckpointSaver 的后处理还原（正常路径）</li>
   *   <li>{@code List<Map>} — 来自手动 checkpoint 创建或旧数据格式（兜底路径）</li>
   * </ul>
   */
  public List<ChatMessage> getMessages() {
    Object raw = internalData.get("messages");
    if (raw == null) return new ArrayList<>();
    return convertToChatMessages(raw);
  }

  /**
   * 存储消息列表，将 ChatMessage 转为可序列化的 Map 后存储。
   *
   * <p>必须转为 Map，因为 langgraph4j 的 checkpoint 机制使用 Java 序列化做深拷贝，
   * 而 ChatMessage 未实现 Serializable。Map 格式同时兼容 Jackson 序列化（写数据库）
   * 和 Java 序列化（内存深拷贝）两条路径。</p>
   */
  public void setMessages(List<ChatMessage> messages) {
    internalData.put("messages", toSerializableMaps(messages));
  }

  /**
   * 将 ChatMessage 列表转为可序列化的 Map 列表。
   *
   * <p>保留 @class 类型标记和每个子类的差异化字段（如 ToolExecutionResultMessage 的
   * id + toolName），确保 JdbcCheckpointSaver 反序列化时能精确还原。</p>
   */
  private static List<Map<String, Object>> toSerializableMaps(List<ChatMessage> messages) {
    if (messages == null) return new ArrayList<>();
    List<Map<String, Object>> result = new ArrayList<>();
    for (ChatMessage msg : messages) {
      Map<String, Object> map = new HashMap<>();
      map.put("@class", msg.getClass().getName());
      String text = "";
      String type = "UNKNOWN";
      if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
        text = um.singleText();
        type = "USER";
      } else if (msg instanceof dev.langchain4j.data.message.AiMessage am) {
        text = am.text() != null ? am.text() : "";
        type = "AI";
      } else if (msg instanceof dev.langchain4j.data.message.SystemMessage sm) {
        text = sm.text() != null ? sm.text() : "";
        type = "SYSTEM";
      } else if (msg instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tem) {
        text = tem.text() != null ? tem.text() : "";
        type = "TOOL_RESULT";
        // 保留工具调用链的关键字段
        map.put("id", tem.id() != null ? tem.id() : "");
        map.put("toolName", tem.toolName() != null ? tem.toolName() : "");
      } else {
        text = msg.text() != null ? msg.text() : "";
      }
      map.put("text", text);
      map.put("type", type);
      result.add(map);
    }
    return result;
  }

  public String getCurrentDepartment() {
    return value("currentDepartment").map(String.class::cast).orElse("CUSTOMER_SERVICE");
  }

  public void setCurrentDepartment(String currentDepartment) {
    internalData.put("currentDepartment", currentDepartment);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> getContextData() {
    return (Map<String, Object>) value("contextData").orElse(new HashMap<>());
  }

  public void setContextData(Map<String, Object> contextData) {
    internalData.put("contextData", contextData);
  }

  public boolean isRequireHumanApproval() {
    return value("requireHumanApproval").map(Boolean.class::cast).orElse(false);
  }

  public void setRequireHumanApproval(boolean requireHumanApproval) {
    internalData.put("requireHumanApproval", requireHumanApproval);
  }

  /**
   * 获取工作流迭代计数器（用于熔断检测）。
   *
   * <p>每次图流转进入核心 LLM 节点（customerServiceNode / inventoryNode）时递增。
   * 当计数超过安全阈值 {@code MAX_LOOPS} 时，触发熔断，路由到 Fallback Node。</p>
   */
  public int getToolCallCount() {
    return value("toolCallCount").map(v -> {
      if (v instanceof Number) return ((Number) v).intValue();
      return 0;
    }).orElse(0);
  }

  public void setToolCallCount(int toolCallCount) {
    internalData.put("toolCallCount", toolCallCount);
  }

  /**
   * 递增迭代计数器并返回递增后的值。
   */
  public int incrementToolCallCount() {
    int count = getToolCallCount() + 1;
    internalData.put("toolCallCount", count);
    return count;
  }
}
