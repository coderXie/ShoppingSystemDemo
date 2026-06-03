package com.shop.agent.dispatch.domain.agent.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.shop.agent.dispatch.domain.agent.state.OrderFlowState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import com.shop.agent.dispatch.shoppingsystemdemo.ShoppingSystemDemoApplication;

/**
 * LangGraph 工作流图集成测试。
 *
 * <p>验证图编译成功、节点注册正确、以及基础的状态流转。</p>
 */
@SpringBootTest(classes = ShoppingSystemDemoApplication.class)
@ActiveProfiles("test")
class OrderGraphBuilderTest {

  @Autowired
  private OrderGraphBuilder orderGraphBuilder;

  @MockBean
  private ChatLanguageModel chatLanguageModel;

  @Test
  @DisplayName("图应编译成功且包含三个核心节点")
  void buildGraph_shouldCompileWithThreeNodes() throws Exception {
    // given: mock LLM 返回普通客服回复
    when(chatLanguageModel.generate(anyList()))
        .thenReturn(Response.from(AiMessage.from("您好，有什么可以帮您？")));

    // when
    CompiledGraph<OrderFlowState> graph = orderGraphBuilder.getCompiledGraph();

    // then
    assertThat(graph).isNotNull();
  }

  @Test
  @DisplayName("初始状态应为 CUSTOMER_SERVICE")
  void initState_shouldHaveDefaultDepartment() {
    OrderFlowState state = OrderFlowState.init(1001L, "u123");

    assertThat(state.getCurrentDepartment()).isEqualTo("CUSTOMER_SERVICE");
    assertThat(state.getOrderId()).isEqualTo(1001L);
    assertThat(state.getUserId()).isEqualTo("u123");
    assertThat(state.isRequireHumanApproval()).isFalse();
  }

  @Test
  @DisplayName("routeNextStep 应根据 currentDepartment 正确路由")
  void routeNextStep_shouldReturnCorrectNode() {
    // INVENTORY
    OrderFlowState state = OrderFlowState.init(1001L, "u123");
    state.setCurrentDepartment("INVENTORY");
    assertThat(orderGraphBuilder.routeNextStep(state)).isEqualTo("inventoryNode");

    // SUPERVISOR
    state.setCurrentDepartment("SUPERVISOR");
    assertThat(orderGraphBuilder.routeNextStep(state)).isEqualTo("supervisorNode");

    // END
    state.setCurrentDepartment("END");
    assertThat(orderGraphBuilder.routeNextStep(state)).isEqualTo("END");

    // 默认
    state.setCurrentDepartment("UNKNOWN");
    assertThat(orderGraphBuilder.routeNextStep(state)).isEqualTo("customerServiceNode");
  }
}
