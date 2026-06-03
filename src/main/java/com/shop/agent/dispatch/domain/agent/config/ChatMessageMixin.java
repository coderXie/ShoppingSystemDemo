package com.shop.agent.dispatch.domain.agent.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.langchain4j.data.message.ChatMessage;

/**
 * Jackson Mix-in，为 {@link ChatMessage} 接口及其所有实现类添加多态类型信息，
 * 使得包含 {@link ChatMessage} 的图状态可以正确序列化/反序列化到数据库。
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
    property = "@class"
)
public abstract class ChatMessageMixin {

  @JsonProperty
  abstract String text();
}
