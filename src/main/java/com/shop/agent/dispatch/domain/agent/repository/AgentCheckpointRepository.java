package com.shop.agent.dispatch.domain.agent.repository;

import com.shop.agent.dispatch.domain.agent.entity.AgentCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Checkpoint 数据访问层。
 */
@Repository
public interface AgentCheckpointRepository extends JpaRepository<AgentCheckpoint, String> {
}
