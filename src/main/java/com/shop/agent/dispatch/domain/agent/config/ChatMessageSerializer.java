package com.shop.agent.dispatch.domain.agent.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.util.List;

/**
 * {@link ChatMessage} 多态序列化器。
 *
 * <p>将 LangChain4j 的各种 ChatMessage 实现类序列化为带 {@code @class} 类型标记的 JSON 对象，
 * 确保从数据库反序列化时能 100% 原样还原出包括 {@link ToolExecutionResultMessage} 在内的完整消息上下文。</p>
 *
 * <p>输出格式示例：</p>
 * <pre>
 * // UserMessage
 * {"@class":"dev.langchain4j.data.message.UserMessage","type":"USER","text":"你好"}
 *
 * // AiMessage（含文本）
 * {"@class":"dev.langchain4j.data.message.AiMessage","type":"AI","text":"您好！"}
 *
 * // AiMessage（含工具调用请求）
 * {"@class":"dev.langchain4j.data.message.AiMessage","type":"AI","text":"",
 *  "toolExecutionRequests":[{"name":"getOrderDetail","arguments":"{\"orderId\":1001}","id":"call_abc"}]}
 *
 * // ToolExecutionResultMessage
 * {"@class":"dev.langchain4j.data.message.ToolExecutionResultMessage","type":"TOOL_RESULT",
 *  "text":"订单号=1001, 状态=SHIPPED","id":"call_abc","toolName":"getOrderDetail"}
 * </pre>
 */
public class ChatMessageSerializer extends JsonSerializer<ChatMessage> {

  @Override
  public void serialize(ChatMessage msg, JsonGenerator gen, SerializerProvider provider)
      throws IOException {
    gen.writeStartObject();

    // 写入类型元数据——反序列化时的关键判据
    gen.writeStringField("@class", msg.getClass().getName());

    // 按具体类型写入差异化字段
    if (msg instanceof UserMessage um) {
      gen.writeStringField("type", "USER");
      gen.writeStringField("text", um.singleText());

    } else if (msg instanceof AiMessage am) {
      gen.writeStringField("type", "AI");
      gen.writeStringField("text", am.text() != null ? am.text() : "");
      // 保留工具调用请求（模型请求调用工具时的中间状态）
      if (am.hasToolExecutionRequests()) {
        writeToolExecutionRequests(am.toolExecutionRequests(), gen);
      }

    } else if (msg instanceof SystemMessage sm) {
      gen.writeStringField("type", "SYSTEM");
      gen.writeStringField("text", sm.text() != null ? sm.text() : "");

    } else if (msg instanceof ToolExecutionResultMessage tem) {
      gen.writeStringField("type", "TOOL_RESULT");
      gen.writeStringField("text", tem.text() != null ? tem.text() : "");
      // 关键：保留 toolCallId 和 toolName，这是恢复工具调用链的必要信息
      gen.writeStringField("id", tem.id() != null ? tem.id() : "");
      gen.writeStringField("toolName", tem.toolName() != null ? tem.toolName() : "");

    } else {
      // 兜底：未知子类型，尽量保留文本
      gen.writeStringField("type", "UNKNOWN");
      gen.writeStringField("text", msg.text() != null ? msg.text() : "");
    }

    gen.writeEndObject();
  }

  /**
   * 写入 AiMessage 中的工具调用请求列表。
   */
  private void writeToolExecutionRequests(List<ToolExecutionRequest> requests, JsonGenerator gen)
      throws IOException {
    gen.writeArrayFieldStart("toolExecutionRequests");
    for (ToolExecutionRequest req : requests) {
      gen.writeStartObject();
      gen.writeStringField("name", req.name() != null ? req.name() : "");
      gen.writeStringField("arguments", req.arguments() != null ? req.arguments() : "");
      if (req.id() != null) {
        gen.writeStringField("id", req.id());
      }
      gen.writeEndObject();
    }
    gen.writeEndArray();
  }

  @Override
  public Class<ChatMessage> handledType() {
    return ChatMessage.class;
  }
}
