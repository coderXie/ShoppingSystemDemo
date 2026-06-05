package com.shop.agent.dispatch.domain.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时确保 agent_checkpoints 表存在且 checkpoint_json 列为 LONGTEXT。
 *
 * <p>表不存在 → 创建；表已存在 → 仅修正列类型（保留数据）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckpointTableFix {

  private final JdbcTemplate jdbcTemplate;

  @PostConstruct
  public void fix() {
    try {
      // 检查表是否存在
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM information_schema.TABLES "
              + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_checkpoints'",
          Integer.class
      );

      if (count == null || count == 0) {
        // 表不存在，创建
        jdbcTemplate.execute(
            "CREATE TABLE agent_checkpoints ("
                + "thread_id VARCHAR(128) PRIMARY KEY, "
                + "checkpoint_json LONGTEXT NOT NULL, "
                + "update_time DATETIME"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );
        log.info("【CheckpointTableFix】agent_checkpoints 表已创建");
      } else {
        // 表已存在，修正列类型（MODIFY 不会丢数据）
        jdbcTemplate.execute(
            "ALTER TABLE agent_checkpoints MODIFY COLUMN checkpoint_json LONGTEXT NOT NULL"
        );
        log.info("【CheckpointTableFix】agent_checkpoints.checkpoint_json 已修正为 LONGTEXT");
      }
    } catch (Exception e) {
      log.error("【CheckpointTableFix】处理失败", e);
    }
  }
}
