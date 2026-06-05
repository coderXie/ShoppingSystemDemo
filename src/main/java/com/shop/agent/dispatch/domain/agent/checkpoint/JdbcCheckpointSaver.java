package com.shop.agent.dispatch.domain.agent.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.agent.dispatch.domain.agent.entity.AgentCheckpoint;
import com.shop.agent.dispatch.domain.agent.repository.AgentCheckpointRepository;
import dev.langchain4j.data.message.ChatMessage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.stereotype.Component;

/**
 * 基于数据库的 Checkpoint 持久化实现。
 *
 * <p>继承 {@link AbstractCheckpointSaver} 以获得线程安全的锁机制。
 * 将 {@link Checkpoint} 列表序列化为 JSON 后存储到 {@code agent_checkpoints} 表，
 * 支持 JVM 重启后的断点续传。</p>
 *
 * <h3>ChatMessage 多态序列化策略：</h3>
 * <p>反序列化时，对 state 中的 {@code messages} 字段执行后处理——将 Jackson 还原的
 * {@code LinkedHashMap} 通过 {@link ObjectMapper#treeToValue} 二次转换为具体的
 * {@link ChatMessage} 子类。这确保了从数据库恢复的对话历史中包含完整的
 * {@code ToolExecutionResultMessage}（含 id + toolName），不会因字段丢失导致
 * 大模型工具调用链断裂。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcCheckpointSaver extends AbstractCheckpointSaver {

  private final AgentCheckpointRepository repository;
  private final ObjectMapper objectMapper;

  @Override
  protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) throws Exception {
    String threadId = threadId(config);
    Optional<AgentCheckpoint> record = repository.findById(threadId);
    if (record.isPresent()) {
      return deserializeList(record.get().getCheckpointJson());
    }
    return new LinkedList<>();
  }

  @Override
  protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
      Checkpoint checkpoint) throws Exception {
    saveCheckpoints(config, checkpoints);
  }

  @Override
  protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
      Checkpoint checkpoint) throws Exception {
    saveCheckpoints(config, checkpoints);
  }

  @Override
  protected BaseCheckpointSaver.Tag releaseCheckpoints(RunnableConfig config,
      LinkedList<Checkpoint> checkpoints) throws Exception {
    saveCheckpoints(config, checkpoints);
    String threadId = threadId(config);
    log.info("【Checkpoint】线程 {} 的 checkpoints 已释放", threadId);
    return new BaseCheckpointSaver.Tag(threadId, checkpoints);
  }

  private void saveCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints)
      throws JsonProcessingException {
    String threadId = threadId(config);
    String json = objectMapper.writeValueAsString(checkpoints);

    AgentCheckpoint entity = AgentCheckpoint.builder()
        .threadId(threadId)
        .checkpointJson(json)
        .updateTime(LocalDateTime.now())
        .build();
    repository.save(entity);
    log.debug("【Checkpoint】线程 {} 已持久化 {} 个 checkpoint", threadId, checkpoints.size());
  }

  /**
   * 反序列化 Checkpoint 列表，并对 state 中的 messages 字段执行 ChatMessage 还原。
   *
   * <p>Jackson 将 {@code Map<String, Object>} 类型的 state 反序列化时，
   * 嵌套的 ChatMessage 对象会被还原为 {@code LinkedHashMap}（因为 Jackson 不知道
   * Map 中的值应该是什么具体类型）。本方法在反序列化完成后，通过 {@link ObjectMapper#treeToValue}
   * 将这些 LinkedHashMap 二次转换为具体的 ChatMessage 子类。</p>
   */
  private LinkedList<Checkpoint> deserializeList(String json) throws Exception {
    JsonNode root = objectMapper.readTree(json);
    LinkedList<Checkpoint> list = new LinkedList<>();

    if (root.isArray()) {
      for (JsonNode node : root) {
        String id = getFieldText(node, "id");
        String nodeId = getFieldText(node, "nodeId");
        String nextNodeId = getFieldText(node, "nextNodeId");

        // 还原 state Map
        Map<String, Object> state = new HashMap<>();
        JsonNode stateNode = node.get("state");
        if (stateNode != null && stateNode.isObject()) {
          state = objectMapper.treeToValue(stateNode, Map.class);
        }

        // ===== 核心后处理：将 messages 中的 LinkedHashMap 还原为 ChatMessage =====
        restoreChatMessages(state);

        list.add(Checkpoint.builder()
            .id(id)
            .state(state)
            .nodeId(nodeId)
            .nextNodeId(nextNodeId)
            .build());
      }
    }

    log.debug("【Checkpoint】反序列化完成，共 {} 个 checkpoint", list.size());
    return list;
  }

  /**
   * 将 state Map 中的 messages 字段从 {@code List<LinkedHashMap>} 还原为
   * {@code List<ChatMessage>}。
   *
   * <p>Jackson 反序列化 {@code Map<String, Object>} 时，嵌套对象会变成 LinkedHashMap。
   * 本方法利用 {@link ObjectMapper#treeToValue} 将每个 LinkedHashMap 重新走一遍
   * 反序列化流程——此时 {@link com.shop.agent.dispatch.domain.agent.config.ChatMessageDeserializer}
   * 会根据 {@code @class} 标记精确还原出 UserMessage / AiMessage / SystemMessage /
   * ToolExecutionResultMessage。</p>
   */
  @SuppressWarnings("unchecked")
  private void restoreChatMessages(Map<String, Object> state) {
    Object messagesRaw = state.get("messages");
    if (!(messagesRaw instanceof List<?> list) || list.isEmpty()) {
      return;
    }

    List<ChatMessage> chatMessages = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof ChatMessage cm) {
        // 已经是 ChatMessage（可能是新写入的 checkpoint）
        chatMessages.add(cm);
      } else if (item instanceof Map<?, ?> map) {
        // LinkedHashMap → 通过 ObjectMapper 二次转换为 ChatMessage
        try {
          JsonNode msgNode = objectMapper.valueToTree(map);
          ChatMessage cm = objectMapper.treeToValue(msgNode, ChatMessage.class);
          if (cm != null) {
            chatMessages.add(cm);
          }
        } catch (Exception e) {
          log.warn("【Checkpoint】ChatMessage 还原失败，跳过该消息: {}", map, e);
        }
      }
    }

    // 用还原后的 ChatMessage 列表替换原始的 Map 列表
    state.put("messages", chatMessages);
    log.debug("【Checkpoint】messages 还原完成，共 {} 条", chatMessages.size());
  }

  private String getFieldText(JsonNode node, String fieldName) {
    JsonNode field = node.get(fieldName);
    if (field != null && !field.isNull() && !field.isMissingNode()) {
      return field.asText();
    }
    return null;
  }
}
