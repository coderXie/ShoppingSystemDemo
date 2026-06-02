package com.shop.agent.dispatch.domain.agent.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.agent.dispatch.domain.agent.entity.AgentCheckpoint;
import com.shop.agent.dispatch.domain.agent.repository.AgentCheckpointRepository;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import lombok.Data;
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
    List<CheckpointDto> dtos = checkpoints.stream().map(this::toDto).toList();
    String json = objectMapper.writeValueAsString(dtos);

    AgentCheckpoint entity = AgentCheckpoint.builder()
        .threadId(threadId)
        .checkpointJson(json)
        .updateTime(LocalDateTime.now())
        .build();
    repository.save(entity);
    log.debug("【Checkpoint】线程 {} 已持久化 {} 个 checkpoint", threadId, checkpoints.size());
  }

  private LinkedList<Checkpoint> deserializeList(String json) throws Exception {
    List<CheckpointDto> dtos = objectMapper.readValue(json, new TypeReference<List<CheckpointDto>>() {
    });
    LinkedList<Checkpoint> list = new LinkedList<>();
    for (CheckpointDto dto : dtos) {
      list.add(Checkpoint.builder()
          .id(dto.getId())
          .state(dto.getState())
          .nodeId(dto.getNodeId())
          .nextNodeId(dto.getNextNodeId())
          .build());
    }
    return list;
  }

  private CheckpointDto toDto(Checkpoint checkpoint) {
    CheckpointDto dto = new CheckpointDto();
    dto.setId(checkpoint.getId());
    dto.setState(checkpoint.getState());
    dto.setNodeId(checkpoint.getNodeId());
    dto.setNextNodeId(checkpoint.getNextNodeId());
    return dto;
  }

  /**
   * 用于 Jackson 序列化的简单 DTO。
   */
  @Data
  public static class CheckpointDto {
    private String id;
    private java.util.Map<String, Object> state;
    private String nodeId;
    private String nextNodeId;
  }
}
