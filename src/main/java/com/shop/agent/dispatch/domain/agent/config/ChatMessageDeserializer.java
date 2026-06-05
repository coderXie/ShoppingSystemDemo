package com.shop.agent.dispatch.domain.agent.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChatMessage} 多态反序列化器。
 *
 * <p>根据 JSON 中的 {@code @class} 或 {@code type} 字段，将 JSON 对象还原为
 * LangChain4j 的具体 ChatMessage 实现类。支持两种判据的优先级：</p>
 * <ol>
 *   <li>{@code @class}（Jackson 标准多态标记，精确匹配完整类名）</li>
 *   <li>{@code type}（手动标记，兜底兼容旧数据格式）</li>
 * </ol>
 *
 * <p>特别处理 {@link ToolExecutionResultMessage}，还原其 {@code id} 和 {@code toolName} 字段，
 * 确保工具调用链在断点续传后不会断裂。</p>
 */
public class ChatMessageDeserializer extends JsonDeserializer<ChatMessage> {

  @Override
  public ChatMessage deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    ObjectMapper mapper = (ObjectMapper) p.getCodec();
    JsonNode node = mapper.readTree(p);

    // 提取判据字段：优先 @class，其次 type
    String className = getField(node, "@class");
    String type = getField(node, "type");
    String text = getField(node, "text");

    // 空文本消息跳过（UserMessage 构造器不允许空 text）
    if (text == null || text.trim().isEmpty()) {
      // ToolExecutionResultMessage 允许空 text，需要特殊处理
      if (!isToolResultType(className, type)) {
        return null;
      }
      text = "";
    }

    try {
      // ===== 优先级 1：通过 @class 精确还原 =====
      if (className != null && !className.isEmpty()) {
        return fromClassName(className, node, text);
      }

      // ===== 优先级 2：通过 type 兜底还原（兼容旧数据格式） =====
      if (type != null) {
        return fromType(type, node, text);
      }

      // 最终兜底：当作 UserMessage
      return new UserMessage(text);

    } catch (Exception e) {
      // langchain4j 构造器可能因 text 格式校验抛异常，安全跳过
      return null;
    }
  }

  /**
   * 通过完整类名精确还原具体类型。
   */
  private ChatMessage fromClassName(String className, JsonNode node, String text) {
    if (className.contains("UserMessage")) {
      return new UserMessage(text);
    }
    if (className.contains("AiMessage")) {
      return buildAiMessage(node, text);
    }
    if (className.contains("SystemMessage")) {
      return new SystemMessage(text);
    }
    if (className.contains("ToolExecutionResultMessage")) {
      return buildToolExecutionResultMessage(node, text);
    }
    // 未知子类型兜底
    return new UserMessage(text);
  }

  /**
   * 通过 type 标记兜底还原（兼容手动序列化格式）。
   */
  private ChatMessage fromType(String type, JsonNode node, String text) {
    return switch (type.toUpperCase()) {
      case "USER" -> new UserMessage(text);
      case "AI" -> buildAiMessage(node, text);
      case "SYSTEM" -> new SystemMessage(text);
      case "TOOL_RESULT" -> buildToolExecutionResultMessage(node, text);
      default -> new UserMessage(text);
    };
  }

  /**
   * 还原 AiMessage，支持文本内容和工具调用请求。
   */
  private AiMessage buildAiMessage(JsonNode node, String text) {
    // 检查是否包含工具调用请求
    JsonNode requestsNode = node.get("toolExecutionRequests");
    if (requestsNode != null && requestsNode.isArray() && !requestsNode.isEmpty()) {
      List<ToolExecutionRequest> requests = new ArrayList<>();
      for (JsonNode reqNode : requestsNode) {
        String name = getField(reqNode, "name");
        String arguments = getField(reqNode, "arguments");
        String id = getField(reqNode, "id");
        ToolExecutionRequest.Builder builder = ToolExecutionRequest.builder()
            .name(name != null ? name : "")
            .arguments(arguments != null ? arguments : "{}");
        if (id != null) {
          builder.id(id);
        }
        requests.add(builder.build());
      }
      return AiMessage.from(requests);
    }
    return new AiMessage(text);
  }

  /**
   * 还原 ToolExecutionResultMessage，恢复 toolCallId 和 toolName。
   *
   * <p>这两个字段是工具调用链的关键：大模型需要通过 toolCallId 将工具执行结果
   * 与之前的工具调用请求关联起来。断点续传后如果丢失这些字段，
   * 大模型会认为工具调用链断裂，可能重新发起调用导致死循环。</p>
   */
  private ToolExecutionResultMessage buildToolExecutionResultMessage(JsonNode node, String text) {
    String id = getField(node, "id");
    String toolName = getField(node, "toolName");
    return ToolExecutionResultMessage.from(
        id != null ? id : "",
        toolName != null ? toolName : "",
        text
    );
  }

  /**
   * 判断是否为 ToolExecutionResultMessage 类型（允许空 text）。
   */
  private boolean isToolResultType(String className, String type) {
    if (className != null && className.contains("ToolExecutionResultMessage")) {
      return true;
    }
    return "TOOL_RESULT".equalsIgnoreCase(type);
  }

  /**
   * 安全读取 JSON 字段值。
   */
  private String getField(JsonNode node, String fieldName) {
    JsonNode field = node.get(fieldName);
    if (field != null && !field.isNull() && !field.isMissingNode()) {
      return field.asText();
    }
    return null;
  }
}
