package com.shop.agent.dispatch.domain.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.shop.agent.dispatch.domain.agent.graph.OrderGraphBuilder;
import com.shop.agent.dispatch.domain.agent.state.OrderFlowState;
import com.shop.agent.dispatch.dto.AgentResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shop.agent.dispatch.shoppingsystemdemo.ShoppingSystemDemoApplication;

/**
 * Agent 会话服务单元测试。
 *
 * <p>通过 mock {@link CompiledGraph} 来验证 {@code chat()} 与 {@code approve()} 的核心逻辑，
 * 避免真正启动 LangGraph 工作流。</p>
 */
@SpringBootTest(classes = ShoppingSystemDemoApplication.class)
@ActiveProfiles("test")
@Transactional
class AgentSessionServiceTest {

  @Autowired
  private AgentSessionService agentSessionService;

  @MockBean
  private OrderGraphBuilder orderGraphBuilder;

  @MockBean
  private dev.langchain4j.model.chat.ChatLanguageModel chatLanguageModel;

  @org.mockito.Mock
  private CompiledGraph<OrderFlowState> compiledGraph;

  @org.mockito.Mock
  private StateSnapshot<OrderFlowState> snapshot;

  @BeforeEach
  void setUp() {
    when(orderGraphBuilder.getCompiledGraph()).thenReturn(compiledGraph);
  }

  @SuppressWarnings("unchecked")
  private AsyncGenerator.Cancellable<NodeOutput<OrderFlowState>> mockStream(
      List<NodeOutput<OrderFlowState>> outputs) {
    AsyncGenerator.Cancellable<NodeOutput<OrderFlowState>> stream =
        org.mockito.Mockito.mock(
            AsyncGenerator.Cancellable.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));

    java.util.Iterator<NodeOutput<OrderFlowState>> iter = outputs.iterator();
    when(stream.next()).thenAnswer(inv -> iter.hasNext()
        ? org.bsc.async.AsyncGenerator.Data.of(iter.next())
        : org.bsc.async.AsyncGenerator.Data.done());
    when(stream.executor()).thenReturn(java.util.concurrent.Executors.newSingleThreadExecutor());

    return stream;
  }

  @Test
  @DisplayName("chat 新会话应初始化状态并执行图节点")
  void chat_newSession_shouldInitStateAndExecute() throws Exception {
    // given: 没有已有快照（新会话）
    when(compiledGraph.getState(any(RunnableConfig.class))).thenReturn(null);
    when(compiledGraph.updateState(any(RunnableConfig.class), any())).thenReturn(null);

    OrderFlowState finalState = OrderFlowState.init(1001L, "u123");
    finalState.getMessages().add(new AiMessage("您好，有什么可以帮您？"));

    NodeOutput<OrderFlowState> output = NodeOutput.of("customerServiceNode", finalState);
    AsyncGenerator.Cancellable<NodeOutput<OrderFlowState>> stream = mockStream(List.of(output));
    when(compiledGraph.stream(any(GraphInput.class), any(RunnableConfig.class)))
        .thenReturn(stream);

    // when
    AgentResponse response = agentSessionService.chat(1001L, "u123", "我要查物流");

    // then
    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isIn("RUNNING", "COMPLETED");
    assertThat(response.getExecutedNodes()).contains("customerServiceNode");
    assertThat(response.getLatestAiMessage()).isEqualTo("您好，有什么可以帮您？");
    assertThat(response.isRequireHumanApproval()).isFalse();
  }

  @Test
  @DisplayName("chat 已有会话应从 Checkpoint 恢复并追加消息")
  void chat_existingSession_shouldResumeFromCheckpoint() throws Exception {
    // given: 已有快照
    OrderFlowState existingState = OrderFlowState.init(1001L, "u123");
    existingState.getMessages().add(new UserMessage("之前的消息"));
    existingState.setCurrentDepartment("INVENTORY");

    when(snapshot.state()).thenReturn(existingState);
    when(compiledGraph.getState(any(RunnableConfig.class))).thenReturn(snapshot);
    when(compiledGraph.updateState(any(RunnableConfig.class), any())).thenReturn(null);

    OrderFlowState finalState = OrderFlowState.init(1001L, "u123");
    finalState.getMessages().add(new UserMessage("之前的消息"));
    finalState.getMessages().add(new AiMessage("库存检查完毕"));
    finalState.setCurrentDepartment("CUSTOMER_SERVICE");

    NodeOutput<OrderFlowState> output = NodeOutput.of("inventoryNode", finalState);
    AsyncGenerator.Cancellable<NodeOutput<OrderFlowState>> stream = mockStream(List.of(output));
    when(compiledGraph.stream(any(GraphInput.class), any(RunnableConfig.class)))
        .thenReturn(stream);

    // when
    AgentResponse response = agentSessionService.chat(1001L, "u123", "继续");

    // then
    assertThat(response).isNotNull();
    assertThat(response.getExecutedNodes()).contains("inventoryNode");
  }

  @Test
  @DisplayName("chat 图执行异常应包装为 RuntimeException")
  void chat_whenGraphThrows_shouldWrapException() throws Exception {
    // given
    when(compiledGraph.getState(any(RunnableConfig.class))).thenReturn(null);
    when(compiledGraph.updateState(any(RunnableConfig.class), any())).thenReturn(null);
    when(compiledGraph.stream(any(GraphInput.class), any(RunnableConfig.class)))
        .thenThrow(new RuntimeException("图节点崩溃"));

    // when & then
    assertThatThrownBy(() -> agentSessionService.chat(1001L, "u123", "test"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("处理用户消息失败");
  }

  @Test
  @DisplayName("approve 应恢复中断的图并返回 COMPLETED")
  void approve_shouldResumeAndComplete() throws Exception {
    // given
    OrderFlowState state = OrderFlowState.init(1001L, "u123");
    state.getMessages().add(new AiMessage("审批通过"));
    state.setCurrentDepartment("END");
    state.setRequireHumanApproval(false);

    when(snapshot.state()).thenReturn(state);
    when(compiledGraph.getState(any(RunnableConfig.class))).thenReturn(snapshot);
    when(compiledGraph.updateState(any(RunnableConfig.class), any())).thenReturn(null);

    NodeOutput<OrderFlowState> output = NodeOutput.of("supervisorNode", state);
    AsyncGenerator.Cancellable<NodeOutput<OrderFlowState>> stream = mockStream(List.of(output));
    when(compiledGraph.stream(any(GraphInput.class), any(RunnableConfig.class)))
        .thenReturn(stream);

    // when
    AgentResponse response = agentSessionService.approve(1001L, "APPROVED", "同意退款");

    // then
    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo("COMPLETED");
    assertThat(response.getExecutedNodes()).contains("supervisorNode");
    assertThat(response.getLatestAiMessage()).isEqualTo("审批通过");
    assertThat(response.isRequireHumanApproval()).isFalse();
  }

  @Test
  @DisplayName("approve 当找不到会话状态时应抛出 IllegalArgumentException")
  void approve_whenSnapshotNotFound_shouldThrowException() {
    // given
    when(compiledGraph.getState(any(RunnableConfig.class))).thenReturn(null);

    // when & then
    assertThatThrownBy(() -> agentSessionService.approve(99999L, "APPROVED", "同意"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("找不到该订单的会话状态");
  }

  @Test
  @DisplayName("approve 图恢复异常应包装为 RuntimeException")
  void approve_whenGraphThrows_shouldWrapException() throws Exception {
    // given
    OrderFlowState state = OrderFlowState.init(1001L, "u123");
    when(snapshot.state()).thenReturn(state);
    when(compiledGraph.getState(any(RunnableConfig.class))).thenReturn(snapshot);
    when(compiledGraph.updateState(any(RunnableConfig.class), any())).thenReturn(null);
    when(compiledGraph.stream(any(GraphInput.class), any(RunnableConfig.class)))
        .thenThrow(new RuntimeException("恢复失败"));

    // when & then
    assertThatThrownBy(() -> agentSessionService.approve(1001L, "REJECTED", "不同意"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("审批后执行失败");
  }

  @Test
  @DisplayName("buildResponse 应在需要人工审批时返回 INTERRUPTED 状态")
  void buildResponse_shouldReturnInterruptedWhenApprovalRequired() throws Exception {
    // given: 需要人工审批的状态
    OrderFlowState state = OrderFlowState.init(1001L, "u123");
    state.setRequireHumanApproval(true);
    state.setCurrentDepartment("SUPERVISOR");
    state.getMessages().add(new AiMessage("已提交退款审批"));

    when(compiledGraph.getState(any(RunnableConfig.class))).thenReturn(null);
    when(compiledGraph.updateState(any(RunnableConfig.class), any())).thenReturn(null);

    NodeOutput<OrderFlowState> output = NodeOutput.of("supervisorNode", state);
    AsyncGenerator.Cancellable<NodeOutput<OrderFlowState>> stream = mockStream(List.of(output));
    when(compiledGraph.stream(any(GraphInput.class), any(RunnableConfig.class)))
        .thenReturn(stream);

    // when
    AgentResponse response = agentSessionService.chat(1001L, "u123", "我要退款");

    // then
    assertThat(response.getStatus()).isEqualTo("INTERRUPTED");
    assertThat(response.isRequireHumanApproval()).isTrue();
  }
}
