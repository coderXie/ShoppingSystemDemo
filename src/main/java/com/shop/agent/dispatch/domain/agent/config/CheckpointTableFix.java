package com.shop.agent.dispatch.domain.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 临时修复：重建 agent_checkpoints 表以确保 checkpoint_json 列为 LONGTEXT。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckpointTableFix {

  private final JdbcTemplate jdbcTemplate;

  @PostConstruct
  public void fix() {
    try {
      jdbcTemplate.execute("DROP TABLE IF EXISTS agent_checkpoints");
      jdbcTemplate.execute(
          "CREATE TABLE agent_checkpoints ("
              + "thread_id VARCHAR(128) PRIMARY KEY, "
              + "checkpoint_json LONGTEXT NOT NULL, "
              + "update_time DATETIME"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
      );
      log.info("【CheckpointTableFix】agent_checkpoints 表已重建为 LONGTEXT");
    } catch (Exception e) {
      log.error("【CheckpointTableFix】重建表失败", e);
    }
  }
}
