package com.shop.agent.dispatch.domain.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson 全局配置，支持 {@link ChatMessage} 多态序列化/反序列化。
 *
 * <p>核心机制：注册 {@link ChatMessageModule}（自定义 {@link ChatMessageSerializer} +
 * {@link ChatMessageDeserializer}），在 Jackson 遇到 ChatMessage 类型时自动拦截，
 * 写入 {@code @class} 类型标记并还原具体子类。</p>
 *
 * <p>相比之前的 Mixin 方案，自定义序列化器有以下优势：</p>
 * <ul>
 *   <li>精确控制每个子类的字段写入（如 ToolExecutionResultMessage 的 id + toolName）</li>
 *   <li>在 {@code Map<String, Object>} 嵌套层面也能正确反序列化</li>
 *   <li>兼容旧数据格式（同时支持 @class 和 type 两种判据）</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

  @Bean
  @Primary
  public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(new ParameterNamesModule());
    mapper.registerModule(new ChatMessageModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    // 启用对 record 类型和不可变对象的支持
    mapper.setVisibility(
        com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
    return mapper;
  }
}
