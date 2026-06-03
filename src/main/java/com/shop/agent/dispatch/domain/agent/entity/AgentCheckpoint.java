package com.shop.agent.dispatch.domain.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LangGraph4j Checkpoint 持久化实体，用于将图执行状态保存到数据库，
 * 支持断点续传和跨 JVM 恢复。
 */
@Entity
@Table(name = "agent_checkpoints")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCheckpoint {

  @Id
  @Column(name = "thread_id", length = 128, nullable = false)
  private String threadId;

  /** Checkpoint 列表的 JSON 序列化数据。 */
  @Column(name = "checkpoint_json", nullable = false, columnDefinition = "LONGTEXT")
  private String checkpointJson;

  @Column(name = "update_time")
  private LocalDateTime updateTime;
}
