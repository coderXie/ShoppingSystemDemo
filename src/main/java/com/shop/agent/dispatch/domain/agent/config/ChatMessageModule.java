package com.shop.agent.dispatch.domain.agent.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.langchain4j.data.message.ChatMessage;

/**
 * Jackson 模块，注册 {@link ChatMessage} 的自定义多态序列化器/反序列化器。
 *
 * <p>解决的核心问题：当 Jackson 将 {@code Map<String, Object>} 反序列化时
 * （如 {@code CheckpointDto.state}），嵌套的 ChatMessage 对象会被还原为
 * {@code LinkedHashMap} 而非具体的 ChatMessage 子类。本模块通过注册全局
 * 自定义反序列化器，在 Jackson 遇到 {@code ChatMessage} 类型时自动拦截并
 * 根据 {@code @class} 标记精确还原。</p>
 *
 * <h3>支持的 ChatMessage 子类：</h3>
 * <ul>
 *   <li>{@code UserMessage} — 用户输入消息</li>
 *   <li>{@code AiMessage} — AI 回复消息（含文本和/或工具调用请求）</li>
 *   <li>{@code SystemMessage} — 系统提示消息</li>
 *   <li>{@code ToolExecutionResultMessage} — 工具执行结果（含 id + toolName）</li>
 * </ul>
 *
 * <h3>序列化输出格式：</h3>
 * <pre>
 * {
 *   "@class": "dev.langchain4j.data.message.UserMessage",
 *   "type": "USER",
 *   "text": "你好"
 * }
 * </pre>
 *
 * @see ChatMessageSerializer 自定义序列化器
 * @see ChatMessageDeserializer 自定义反序列化器
 */
public class ChatMessageModule extends SimpleModule {

  public ChatMessageModule() {
    super("ChatMessageModule");
    addSerializer(ChatMessage.class, new ChatMessageSerializer());
    addDeserializer(ChatMessage.class, new ChatMessageDeserializer());
  }
}
