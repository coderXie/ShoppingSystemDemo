package com.shop.agent.dispatch.domain.agent.graph;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;

import com.shop.agent.dispatch.domain.agent.service.RefundApprovalService;
import com.shop.agent.dispatch.domain.agent.state.OrderFlowState;
import com.shop.agent.dispatch.domain.inventory.service.InventoryService;
import com.shop.agent.dispatch.domain.logistics.entity.Logistics;
import com.shop.agent.dispatch.domain.logistics.service.LogisticsService;
import com.shop.agent.dispatch.domain.order.service.OrderService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolExecutionRequestUtil;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.stereotype.Component;

/**
 * 跨境电商协同调度系统的 LangGraph4j 工作流图构建器。
 *
 * <p>构建了包含客服、库存、主管三个核心节点的状态机图，并配置了中断机制
 * 以支持人工审批（Human-in-the-Loop）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderGraphBuilder {

  private final ChatLanguageModel chatLanguageModel;
  private final OrderService orderService;
  private final LogisticsService logisticsService;
  private final InventoryService inventoryService;
  private final RefundApprovalService refundApprovalService;
  private final BaseCheckpointSaver checkpointSaver;

  private CompiledGraph<OrderFlowState> compiledGraph;

  @PostConstruct
  public void init() throws Exception {
    this.compiledGraph = buildGraph();
    log.info("【Graph】工作流图已编译并缓存，支持跨请求复用与断点续传");
  }

  public CompiledGraph<OrderFlowState> getCompiledGraph() {
    return compiledGraph;
  }

  /**
   * 【客服节点】虚拟客服节点。
   *
   * <p>利用大模型分析用户消息意图：
   * <ul>
   *   <li>若用户查询物流，直接调用 {@link LogisticsService#getLogisticsInfo} 获取轨迹并回复；</li>
   *   <li>若用户强烈要求退款，将 currentDepartment 修改为 INVENTORY 并转交库存部门；</li>
   *   <li>其他情况保持 CUSTOMER_SERVICE 并正常回复。</li>
   * </ul>
   *
   * @param state 当前全局状态
   * @return 更新后的状态
   */
  public OrderFlowState customerServiceNode(OrderFlowState state) {
    log.info("【客服节点】开始处理，orderId={}", state.getOrderId());

    List<ChatMessage> messages = new ArrayList<>(state.getMessages());

    SystemMessage systemMsg = new SystemMessage(
        "你是跨境电商智能客服助手。请严格根据用户最后一条诉求判断意图，并在回复开头带上标记：\n"
            + "- 若用户在查询物流/快递/包裹位置，回复以 【INTENT:LOGISTICS】 开头；\n"
            + "- 若用户强烈要求退款、退货、取消订单，回复以 【INTENT:REFUND】 开头；\n"
            + "- 其他情况正常回复，不要带任何标记。"
    );

    List<ChatMessage> promptMessages = new ArrayList<>();
    promptMessages.add(systemMsg);
    // 取最近 6 条消息作为上下文，避免 prompt 过长
    int start = Math.max(0, messages.size() - 6);
    promptMessages.addAll(messages.subList(start, messages.size()));

    Response<AiMessage> response = chatLanguageModel.generate(promptMessages);
    String content = response.content().text();
    log.info("【客服节点】模型输出: {}", content);

    if (content.contains("【INTENT:LOGISTICS】") || content.contains("INTENT:LOGISTICS")) {
      log.info("【客服节点】识别到物流查询意图，orderId={}", state.getOrderId());
      try {
        Logistics logistics = logisticsService.getLogisticsInfo(state.getOrderId());
        String reply = String.format(
            "为您查询到物流信息：物流单号 %s，当前状态 %s，最新位置 %s。",
            logistics.getTrackingNumber(), logistics.getStatus(), logistics.getLastLocation()
        );
        messages.add(new AiMessage(reply));
      } catch (Exception e) {
        log.warn("【客服节点】查询物流失败", e);
        messages.add(new AiMessage("抱歉，暂时无法查询到该订单的物流信息，请稍后再试。"));
      }
      state.setCurrentDepartment("CUSTOMER_SERVICE");

    } else if (content.contains("【INTENT:REFUND】") || content.contains("INTENT:REFUND")) {
      log.info("【客服节点】识别到退款意图，转交库存部门核查，orderId={}", state.getOrderId());
      messages.add(new AiMessage(
          "已收到您的退款诉求，正在转交供应链部门核实库存与补货情况，请稍候。"
      ));
      state.setCurrentDepartment("INVENTORY");

    } else {
      messages.add(response.content());
      state.setCurrentDepartment("CUSTOMER_SERVICE");
    }

    state.setMessages(messages);
    return state;
  }

  /**
   * 【库存节点】虚拟供应链/仓储节点。
   *
   * <p>大模型根据上下文，自动调用 {@code checkProductStock} 与
   * {@code submitRefundApproval} 工具。若确认海外仓彻底缺货无法补发，
   * 则自动提交退款审批，并将 currentDepartment 改为 SUPERVISOR。</p>
   *
   * @param state 当前全局状态
   * @return 更新后的状态
   */
  public OrderFlowState inventoryNode(OrderFlowState state) {
    log.info("【库存节点】开始处理，orderId={}", state.getOrderId());

    List<ChatMessage> messages = new ArrayList<>(state.getMessages());

    // 收集库存节点可用的工具规范
    List<ToolSpecification> toolSpecs = new ArrayList<>();
    toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(inventoryService));
    toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(refundApprovalService));

    SystemMessage systemMsg = new SystemMessage(
        "你是供应链专家。请根据订单上下文分析库存情况。\n"
            + "规则：\n"
            + "1. 如需要检查库存，请调用 checkProductStock 工具；\n"
            + "2. 若确认海外仓彻底缺货且无法补发，必须调用 submitRefundApproval 工具提交退款审批，"
            + "    reason 参数请填写详细的缺货分析报告；\n"
            + "3. 分析完成后，请给出结论性回复，说明库存检查结果及后续处理。"
    );

    List<ChatMessage> promptMessages = new ArrayList<>();
    promptMessages.add(systemMsg);
    promptMessages.addAll(messages);

    Response<AiMessage> response = chatLanguageModel.generate(promptMessages, toolSpecs);
    AiMessage aiMessage = response.content();

    // Tool 调用循环（最多 5 轮，防止无限循环）
    int maxIter = 5;
    int iter = 0;
    while (aiMessage.hasToolExecutionRequests() && iter < maxIter) {
      iter++;
      messages.add(aiMessage);
      log.info("【库存节点】模型请求调用 {} 个工具（第 {} 轮）",
          aiMessage.toolExecutionRequests().size(), iter);

      for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
        String result = executeToolRequest(request);
        messages.add(ToolExecutionResultMessage.from(request, result));
        log.info("【库存节点】工具 {} 执行结果: {}", request.name(), result);
      }

      response = chatLanguageModel.generate(messages, toolSpecs);
      aiMessage = response.content();
    }

    messages.add(aiMessage);
    state.setMessages(messages);

    // 若模型已调用 submitRefundApproval 或结论中明确提到退款审批，则进入主管节点
    String aiText = aiMessage.text();
    boolean refundInitiated = messages.stream()
        .anyMatch(m -> m instanceof ToolExecutionResultMessage
            && ((ToolExecutionResultMessage) m).toolName().equals("submitRefundApproval"));

    if (refundInitiated || aiText.contains("退款审批") || aiText.contains("REFUND_PENDING")) {
      log.info("【库存节点】判定需进入退款审批流程，orderId={}", state.getOrderId());
      state.setCurrentDepartment("SUPERVISOR");
    } else {
      log.info("【库存节点】库存检查完毕，返回客服节点，orderId={}", state.getOrderId());
      state.setCurrentDepartment("CUSTOMER_SERVICE");
    }

    return state;
  }

  /**
   * 【主管节点】虚拟主管审批节点。
   *
   * <p>检查 {@code contextData} 中是否包含管理员审批结果 {@code managerDecision}。
   * 若审批通过（APPROVED），调用 {@link RefundApprovalService#executeFinalRefund}
   * 真正执行退款并结束流程；若尚无审批数据，将 {@code requireHumanApproval}
   * 设为 {@code true} 触发挂起（由 {@code interruptBefore} 机制暂停）。</p>
   *
   * @param state 当前全局状态
   * @return 更新后的状态
   */
  public OrderFlowState supervisorNode(OrderFlowState state) {
    log.info("【主管节点】开始处理，orderId={}", state.getOrderId());

    Map<String, Object> context = state.getContextData();
    Object decision = context.get("managerDecision");

    if ("APPROVED".equals(decision)) {
      log.info("【主管节点】管理员已审批通过，执行最终退款，orderId={}", state.getOrderId());
      boolean success = refundApprovalService.executeFinalRefund(state.getOrderId());

      List<ChatMessage> messages = new ArrayList<>(state.getMessages());
      if (success) {
        messages.add(new AiMessage(
            "主管审批已通过，退款已成功执行，对应商品库存已回滚至海外仓。"
        ));
      } else {
        messages.add(new AiMessage(
            "主管审批已通过，但退款执行时遇到异常（订单状态可能不符合），请联系技术团队。"
        ));
      }
      state.setMessages(messages);
      state.setCurrentDepartment("END");
      state.setRequireHumanApproval(false);

    } else {
      log.info("【主管节点】暂无审批结果，挂起等待人工审核，orderId={}", state.getOrderId());
      state.setRequireHumanApproval(true);
      // currentDepartment 保持 SUPERVISOR，恢复后继续进入本节点
    }

    return state;
  }

  /**
   * 条件边路由：根据 {@code currentDepartment} 决定图的下一次走向。
   *
   * @param state 当前全局状态
   * @return 目标节点名称（或 {@code END}）
   */
  public String routeNextStep(OrderFlowState state) {
    String dept = state.getCurrentDepartment();
    log.info("【路由】currentDepartment={}", dept);
    return switch (dept) {
      case "INVENTORY" -> "inventoryNode";
      case "SUPERVISOR" -> "supervisorNode";
      case "END" -> END;
      default -> "customerServiceNode";
    };
  }

  /**
   * 组装并编译工作流图。
   *
   * <p>图结构：
   * <pre>
   * START -> customerServiceNode
   * customerServiceNode --[routeNextStep]--> customerServiceNode / inventoryNode / supervisorNode / END
   * inventoryNode      --[routeNextStep]--> customerServiceNode / supervisorNode / END
   * supervisorNode     --[routeNextStep]--> customerServiceNode / supervisorNode / END
   * </pre>
   *
   * <p>配置 {@code .interruptBefore("supervisorNode")}，确保在进入主管节点前自动暂停，
   * 等待人工在 {@code contextData} 中写入 {@code managerDecision} 后恢复。</p>
   *
   * @return 编译后的可执行图
   * @throws Exception 图构建异常
   */
  public CompiledGraph<OrderFlowState> buildGraph() throws Exception {
    // 定义 State Schema，所有字段使用 base Channel（LastValue 覆盖策略）
    Map<String, Channel<?>> schema = new HashMap<>();
    schema.put("userId", Channels.<String>base(() -> null));
    schema.put("orderId", Channels.<Long>base(() -> null));
    schema.put("messages", Channels.<List<ChatMessage>>base(() -> new ArrayList<>()));
    schema.put("currentDepartment", Channels.<String>base(() -> "CUSTOMER_SERVICE"));
    schema.put("contextData", Channels.<Map<String, Object>>base(() -> new HashMap<>()));
    schema.put("requireHumanApproval", Channels.<Boolean>base(() -> false));

    StateGraph<OrderFlowState> graph = new StateGraph<>(schema, OrderFlowState::new);

    // 注册三个核心节点：将业务方法包装为 LangGraph4j 的 AsyncNodeAction
    graph.addNode("customerServiceNode",
        AsyncNodeAction.node_async(state -> customerServiceNode(state).data()));
    graph.addNode("inventoryNode",
        AsyncNodeAction.node_async(state -> inventoryNode(state).data()));
    graph.addNode("supervisorNode",
        AsyncNodeAction.node_async(state -> supervisorNode(state).data()));

    // 条件边：所有节点共享同一个路由逻辑
    Map<String, String> routeMapping = Map.of(
        "customerServiceNode", "customerServiceNode",
        "inventoryNode", "inventoryNode",
        "supervisorNode", "supervisorNode",
        END, END
    );

    graph.addConditionalEdges("customerServiceNode",
        AsyncEdgeAction.edge_async(this::routeNextStep), routeMapping);
    graph.addConditionalEdges("inventoryNode",
        AsyncEdgeAction.edge_async(this::routeNextStep), routeMapping);
    graph.addConditionalEdges("supervisorNode",
        AsyncEdgeAction.edge_async(this::routeNextStep), routeMapping);

    // 入口
    graph.addEdge(START, "customerServiceNode");

    // 编译配置：在进入主管节点前中断，并使用数据库 CheckpointSaver 以支持持久化与断点续传
    CompileConfig compileConfig = CompileConfig.builder()
        .interruptBefore("supervisorNode")
        .checkpointSaver(checkpointSaver)
        .build();

    return graph.compile(compileConfig);
  }

  /**
   * 执行单个 Tool 请求，并返回字符串结果。
   *
   * <p>目前支持库存节点涉及的两个核心工具：
   * {@code checkProductStock} 与 {@code submitRefundApproval}。</p>
   *
   * @param request 模型发出的工具执行请求
   * @return 工具执行结果文本
   */
  private String executeToolRequest(ToolExecutionRequest request) {
    String toolName = request.name();
    Map<String, Object> args = ToolExecutionRequestUtil.argumentsAsMap(request.arguments());
    log.debug("【工具执行】name={}, args={}", toolName, args);

    try {
      return switch (toolName) {
        case "checkProductStock" -> {
          Long productId = ((Number) args.get("productId")).longValue();
          int quantity = ((Number) args.get("quantity")).intValue();
          yield String.valueOf(inventoryService.checkProductStock(productId, quantity));
        }
        case "submitRefundApproval" -> {
          Long orderId = ((Number) args.get("orderId")).longValue();
          String reason = (String) args.get("reason");
          refundApprovalService.submitRefundApproval(orderId, reason);
          yield "退款审批已提交成功，订单状态已变更为 REFUND_PENDING";
        }
        default -> throw new IllegalArgumentException("未知工具: " + toolName);
      };
    } catch (Exception e) {
      log.error("【工具执行】工具 {} 执行失败", toolName, e);
      return "执行失败: " + e.getMessage();
    }
  }
}
