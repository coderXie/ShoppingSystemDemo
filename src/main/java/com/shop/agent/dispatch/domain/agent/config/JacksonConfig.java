package com.shop.agent.dispatch.domain.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson 全局配置，支持 {@link ChatMessage} 多态序列化/反序列化。
 *
 * <p>LangGraph4j 的 Checkpoint 中包含 {@code List<ChatMessage>}，
 * 由于 {@link ChatMessage} 是接口，必须通过 {@code @JsonTypeInfo} 告诉 Jackson
 * 在序列化时写入具体实现类名，以便反序列化时正确还原。</p>
 */
@Configuration
public class JacksonConfig {

  @Bean
  @Primary
  public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.addMixIn(ChatMessage.class, ChatMessageMixin.class);
    return mapper;
  }
}
