package com.shop.agent.dispatch.domain.agent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 手动配置类。
 *
 * <p>当 {@code langchain4j-open-ai-spring-boot-starter} 的自动配置未生效时，
 * 通过此类显式创建 {@link ChatLanguageModel} Bean，确保 {@link OrderGraphBuilder} 能正常注入。</p>
 */
@Configuration
public class LangChain4jConfig {

  @Value("${langchain4j.open-ai.chat-model.base-url:https://api.deepseek.com}")
  private String baseUrl;

  @Value("${langchain4j.open-ai.chat-model.api-key:}")
  private String apiKey;

  @Value("${langchain4j.open-ai.chat-model.model-name:deepseek-chat}")
  private String modelName;

  @Value("${langchain4j.open-ai.chat-model.temperature:0.7}")
  private Double temperature;

  @Value("${langchain4j.open-ai.chat-model.max-tokens:2000}")
  private Integer maxTokens;

  @Value("${langchain4j.open-ai.chat-model.timeout:PT60S}")
  private Duration timeout;

  @Bean
  public ChatLanguageModel chatLanguageModel() {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "缺少 DeepSeek API Key，请配置环境变量 DEEPSEEK_API_KEY 或在 application.properties 中设置 langchain4j.open-ai.chat-model.api-key");
    }
    return OpenAiChatModel.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .modelName(modelName)
        .temperature(temperature)
        .maxTokens(maxTokens)
        .timeout(timeout)
        .build();
  }
}
