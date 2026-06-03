package com.shop.agent.dispatch.domain.agent.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.shop.agent.dispatch.domain.agent.entity.AgentCheckpoint;
import com.shop.agent.dispatch.domain.agent.repository.AgentCheckpointRepository;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shop.agent.dispatch.shoppingsystemdemo.ShoppingSystemDemoApplication;

/**
 * JdbcCheckpointSaver 单元测试。
 *
 * <p>验证 Checkpoint 的持久化、加载、更新与释放逻辑。
 * 测试类与目标类同包，可直接调用 {@code protected} 方法。</p>
 */
@SpringBootTest(classes = ShoppingSystemDemoApplication.class)
@ActiveProfiles("test")
@Transactional
class JdbcCheckpointSaverTest {

  @Autowired
  private JdbcCheckpointSaver checkpointSaver;

  @Autowired
  private AgentCheckpointRepository repository;

  @org.springframework.boot.test.mock.mockito.MockBean
  private dev.langchain4j.model.chat.ChatLanguageModel chatLanguageModel;

  private RunnableConfig config;

  @BeforeEach
  void setUp() {
    config = RunnableConfig.builder().threadId("order-1001").build();
  }

  @Test
  @DisplayName("loadCheckpoints 当数据库无记录时应返回空列表")
  void loadCheckpoints_whenNoRecord_shouldReturnEmptyList() throws Exception {
    LinkedList<Checkpoint> checkpoints = checkpointSaver.loadCheckpoints(config);

    assertThat(checkpoints).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("insertedCheckpoint 应将 checkpoint 持久化到数据库")
  void insertedCheckpoint_shouldSaveToDatabase() throws Exception {
    // given
    Checkpoint cp = Checkpoint.builder()
        .id("cp-1")
        .state(Map.of("key", "value"))
        .nodeId("customerServiceNode")
        .nextNodeId("inventoryNode")
        .build();
    LinkedList<Checkpoint> checkpoints = new LinkedList<>();
    checkpoints.add(cp);

    // when
    checkpointSaver.insertedCheckpoint(config, checkpoints, cp);

    // then
    AgentCheckpoint record = repository.findById("order-1001").orElse(null);
    assertThat(record).isNotNull();
    assertThat(record.getCheckpointJson()).contains("cp-1");
    assertThat(record.getCheckpointJson()).contains("customerServiceNode");
  }

  @Test
  @DisplayName("updatedCheckpoint 应更新数据库中的 checkpoint 记录")
  void updatedCheckpoint_shouldUpdateDatabase() throws Exception {
    // given: 先插入一条
    Checkpoint cp1 = Checkpoint.builder()
        .id("cp-1")
        .state(Map.of("key", "v1"))
        .nodeId("node1")
        .nextNodeId("node2")
        .build();
    LinkedList<Checkpoint> checkpoints = new LinkedList<>();
    checkpoints.add(cp1);
    checkpointSaver.insertedCheckpoint(config, checkpoints, cp1);

    // when: 更新为两条
    Checkpoint cp2 = Checkpoint.builder()
        .id("cp-2")
        .state(Map.of("key", "v2"))
        .nodeId("node2")
        .nextNodeId("node3")
        .build();
    checkpoints.add(cp2);
    checkpointSaver.updatedCheckpoint(config, checkpoints, cp2);

    // then
    AgentCheckpoint record = repository.findById("order-1001").orElse(null);
    assertThat(record).isNotNull();
    assertThat(record.getCheckpointJson()).contains("cp-1");
    assertThat(record.getCheckpointJson()).contains("cp-2");
  }

  @Test
  @DisplayName("releaseCheckpoints 应持久化并返回正确 Tag")
  void releaseCheckpoints_shouldSaveAndReturnTag() throws Exception {
    // given
    Checkpoint cp = Checkpoint.builder()
        .id("cp-release")
        .state(Map.of("status", "done"))
        .nodeId("endNode")
        .nextNodeId("END")
        .build();
    LinkedList<Checkpoint> checkpoints = new LinkedList<>();
    checkpoints.add(cp);

    // when
    BaseCheckpointSaver.Tag tag = checkpointSaver.releaseCheckpoints(config, checkpoints);

    // then
    assertThat(tag).isNotNull();
    assertThat(tag.threadId()).isEqualTo("order-1001");
    assertThat(tag.checkpoints()).hasSize(1);

    AgentCheckpoint record = repository.findById("order-1001").orElse(null);
    assertThat(record).isNotNull();
    assertThat(record.getCheckpointJson()).contains("cp-release");
  }

  @Test
  @DisplayName("loadCheckpoints 应正确反序列化已保存的 checkpoint")
  void loadCheckpoints_shouldDeserializeSavedCheckpoints() throws Exception {
    // given
    Checkpoint cp = Checkpoint.builder()
        .id("cp-load")
        .state(Map.of("orderId", 1001L, "dept", "CUSTOMER_SERVICE"))
        .nodeId("customerServiceNode")
        .nextNodeId("supervisorNode")
        .build();
    LinkedList<Checkpoint> checkpoints = new LinkedList<>();
    checkpoints.add(cp);
    checkpointSaver.insertedCheckpoint(config, checkpoints, cp);

    // when
    LinkedList<Checkpoint> loaded = checkpointSaver.loadCheckpoints(config);

    // then
    assertThat(loaded).hasSize(1);
    Checkpoint loadedCp = loaded.getFirst();
    assertThat(loadedCp.getId()).isEqualTo("cp-load");
    assertThat(loadedCp.getNodeId()).isEqualTo("customerServiceNode");
    assertThat(loadedCp.getNextNodeId()).isEqualTo("supervisorNode");
    assertThat(loadedCp.getState()).containsEntry("orderId", 1001);
    assertThat(loadedCp.getState()).containsEntry("dept", "CUSTOMER_SERVICE");
  }

  @Test
  @DisplayName("多个 threadId 的 checkpoint 应互不干扰")
  void multipleThreadIds_shouldBeIsolated() throws Exception {
    // given
    RunnableConfig configA = RunnableConfig.builder().threadId("order-A").build();
    RunnableConfig configB = RunnableConfig.builder().threadId("order-B").build();

    Checkpoint cpA = Checkpoint.builder().id("cp-A").state(Map.of("k", "a")).nodeId("n1").nextNodeId("n2").build();
    Checkpoint cpB = Checkpoint.builder().id("cp-B").state(Map.of("k", "b")).nodeId("n2").nextNodeId("n3").build();

    checkpointSaver.insertedCheckpoint(configA, new LinkedList<>(List.of(cpA)), cpA);
    checkpointSaver.insertedCheckpoint(configB, new LinkedList<>(List.of(cpB)), cpB);

    // when
    LinkedList<Checkpoint> loadedA = checkpointSaver.loadCheckpoints(configA);
    LinkedList<Checkpoint> loadedB = checkpointSaver.loadCheckpoints(configB);

    // then
    assertThat(loadedA).hasSize(1);
    assertThat(loadedA.getFirst().getId()).isEqualTo("cp-A");

    assertThat(loadedB).hasSize(1);
    assertThat(loadedB.getFirst().getId()).isEqualTo("cp-B");
  }
}
