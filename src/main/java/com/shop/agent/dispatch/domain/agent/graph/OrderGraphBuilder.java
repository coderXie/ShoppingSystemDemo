package com.shop.agent.dispatch.domain.agent.graph;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.agent.dispatch.domain.agent.exception.AgentMaxIterationException;
import com.shop.agent.dispatch.domain.agent.service.RefundApprovalService;
import com.shop.agent.dispatch.domain.agent.state.OrderFlowState;
import com.shop.agent.dispatch.domain.inventory.service.InventoryService;
import com.shop.agent.dispatch.domain.logistics.entity.Logistics;
import com.shop.agent.dispatch.domain.logistics.service.LogisticsService;
import com.shop.agent.dispatch.domain.order.entity.Order;
import com.shop.agent.dispatch.domain.order.entity.OrderItem;
import com.shop.agent.dispatch.domain.order.repository.OrderItemRepository;
import com.shop.agent.dispatch.domain.order.service.OrderService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolExecutionRequestUtil;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
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

  /**
   * 全局最大安全迭代阈值——防大模型幻觉死循环熔断。
   *
   * <p>每次图流转进入核心 LLM 节点（customerServiceNode / inventoryNode）时计数器递增。
   * 当计数超过此阈值，说明大模型可能陷入了思维死循环或工具滥用，
   * 立刻强制中断工作流并路由到 Fallback Node。</p>
   */
  private static final int MAX_LOOPS = 5;

  /**
   * Fallback Node 输出的兜底话术——不打扰用户，自动呼叫人工客服。
   */
  private static final String FALLBACK_REPLY =
      "很抱歉，小彦当前处理该订单遇到了一点技术问题，已自动为您呼叫后台人工客服跟进，请稍后。";

  private final ChatLanguageModel chatLanguageModel;
  private final OrderService orderService;
  private final LogisticsService logisticsService;
  private final InventoryService inventoryService;
  private final RefundApprovalService refundApprovalService;
  private final BaseCheckpointSaver checkpointSaver;
  private final OrderItemRepository orderItemRepository;
  private final ObjectMapper objectMapper;

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
   * 【客服节点】智能客服节点。
   *
   * <p>利用大模型分析用户消息意图，具备以下智能能力：
   * <ul>
   *   <li>精准意图识别：通过结构化 JSON 输出，区分物流查询、退款申请、商品咨询、投诉等；</li>
   *   <li>订单状态感知：根据订单当前状态判断用户请求的合理性，主动引导；</li>
   *   <li>上下文记忆：保留最近 10 轮对话，理解多轮对话中的指代和省略；</li>
   *   <li>情感识别：识别用户情绪（着急、生气、满意），调整回复语气。</li>
   * </ul>
   *
   * @param state 当前全局状态
   * @return 更新后的状态
   */
  public OrderFlowState customerServiceNode(OrderFlowState state) {
    // ===== 熔断检测：递增迭代计数器，超过阈值则中断 =====
    int currentCount = state.incrementToolCallCount();
    log.info("【客服节点】开始处理，orderId={}, toolCallCount={}/{}", state.getOrderId(), currentCount, MAX_LOOPS);
    if (currentCount > MAX_LOOPS) {
      log.warn("【熔断】客服节点迭代次数 {} 超过安全阈值 {}，触发熔断，orderId={}",
          currentCount, MAX_LOOPS, state.getOrderId());
      throw new AgentMaxIterationException(state.getOrderId(), currentCount, MAX_LOOPS);
    }

    // 过滤无效消息 + 清理孤立的 ToolExecutionResultMessage
    List<ChatMessage> messages = sanitizeMessages(state.getMessages());

    // ============================================================
    // 方案 C（终极保障）：身份问题直接绕过 LLM 返回硬编码答案
    // 100% 穿透，不依赖模型对 SystemMessage 的遵从度
    // ============================================================
    if (!messages.isEmpty()) {
      ChatMessage lastMsg = messages.get(messages.size() - 1);
      log.info("【客服节点】最后一条消息: type={}, text={}", lastMsg.getClass().getSimpleName(), lastMsg.text());
      if (lastMsg instanceof UserMessage) {
        String userText = ((UserMessage) lastMsg).text();
        if (userText != null && isIdentityQuestion(userText)) {
          String identityReply = "您好！我叫小彦 😊 是您的跨境供应链智能助手，"
              + "专门帮您查询跨境订单、追踪海外仓库存以及解决物流异常。请问有什么可以帮您？";
          messages.add(new AiMessage(identityReply));
          state.setMessages(messages);
          log.info("【客服节点】检测到身份类问题，已直接返回小彦身份回复（绕过 LLM）");
          return state;
        }
      }
    }

    // 对话摘要压缩：超过6轮时对早期对话生成摘要
    if (messages.size() > 6) {
      messages = summarizeHistory(messages);
    }

    // 加载订单上下文
    String orderContext = buildOrderContext(state.getOrderId());

    // 构建智能客服 System Prompt（含增强上下文理解规则）
    SystemMessage systemMsg = buildCustomerServicePrompt(orderContext, state);

    // ============================================================
    // 方案 A：显式列表首位注入法
    // 创建全新 fullMessages 列表，确保 SystemMessage 始终在最前面
    // ============================================================
    List<ChatMessage> fullMessages = new ArrayList<>();

    // 身份锚定：显式注入核心人设 SystemMessage（独立于详细 prompt）
    fullMessages.add(SystemMessage.from(
        "你是一位专业的跨境电商智能供应链助手，名字叫\"小彦\"。"
        + "当用户询问你的身份、名字或\"你是谁\"时，你必须礼貌地回答你叫\"小彦\"。"));

    // 叠加详细能力 System Prompt（订单上下文、意图规则、few-shot 等）
    fullMessages.add(systemMsg);

    // 保留最近 10 条历史消息，增强上下文理解
    int start = Math.max(0, messages.size() - 10);
    fullMessages.addAll(messages.subList(start, messages.size()));

    // 兜底：没有用户消息时添加默认消息
    if (fullMessages.size() <= 2) {
      fullMessages.add(new UserMessage("你好，我想了解这个订单的情况。"));
    }

    // 收集客服节点可用的工具（订单查询、物流查询、库存查询）
    List<ToolSpecification> toolSpecs = new ArrayList<>();
    toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(orderService));
    toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(logisticsService));
    toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(inventoryService));

    // 调用大模型（带工具）
    Response<AiMessage> response = chatLanguageModel.generate(fullMessages, toolSpecs);
    AiMessage aiResponse = response.content();

    // Tool 调用循环（最多 3 轮，防止无限循环）
    int maxIter = 3;
    int iter = 0;
    while (aiResponse != null && aiResponse.hasToolExecutionRequests() && iter < maxIter) {
      iter++;
      messages.add(aiResponse);
      log.info("【客服节点】模型请求调用 {} 个工具（第 {} 轮）",
          aiResponse.toolExecutionRequests().size(), iter);

      for (ToolExecutionRequest request : aiResponse.toolExecutionRequests()) {
        String result = executeToolRequest(request);
        messages.add(ToolExecutionResultMessage.from(request, result));
        log.info("【客服节点】工具 {} 执行结果: {}", request.name(), result);
      }

      // 修复：将工具执行结果拼入 fullMessages 后再调用，避免上下文丢失
      List<ChatMessage> toolLoopMessages = new ArrayList<>(fullMessages);
      int toolStart = Math.max(0, messages.size() - 10);
      toolLoopMessages.addAll(messages.subList(toolStart, messages.size()));
      response = chatLanguageModel.generate(toolLoopMessages, toolSpecs);
      aiResponse = response.content();
    }

    String rawContent = (aiResponse != null && aiResponse.text() != null) ? aiResponse.text() : "";
    log.info("【客服节点】模型原始输出: {}", rawContent);

    // 解析结构化输出
    IntentResult intentResult = parseIntentResult(rawContent, state);
    log.info("【客服节点】意图识别结果: intent={}, confidence={}, orderId={}",
        intentResult.intent, intentResult.confidence, state.getOrderId());

    // 根据意图执行对应操作
    switch (intentResult.intent) {
      case "LOGISTICS":
        handleLogisticsIntent(state, messages, intentResult);
        break;
      case "REFUND":
        handleRefundIntent(state, messages, intentResult);
        break;
      case "PRODUCT":
      case "RECOMMEND":
        handleProductIntent(state, messages, intentResult);
        break;
      default:
        // 普通回复直接返回AI消息（包括INQUIRY、COMPLAINT、ORDER_MODIFY、PAYMENT、COUPON、OTHER）
        messages.add(new AiMessage(intentResult.response));
        state.setCurrentDepartment("END");
        break;
    }

    state.setMessages(messages);
    return state;
  }

  /**
   * 消息清洗：过滤无效消息 + 清理孤立的 ToolExecutionResultMessage。
   *
   * <p>从 checkpoint 恢复的消息历史中，ToolExecutionResultMessage 前面的
   * AiMessage（带 tool_calls）可能在序列化过程中丢失了工具调用请求字段，
   * 导致发给 LLM 时报错"tool 消息没有对应的 tool_calls"。
   * 本方法检测并移除这类孤立的工具结果消息。</p>
   */
  private List<ChatMessage> sanitizeMessages(List<ChatMessage> raw) {
    List<ChatMessage> cleaned = new ArrayList<>();
    for (ChatMessage msg : raw) {
      if (msg == null) continue;
      // 跳过空文本消息（但 ToolExecutionResultMessage 允许空 text）
      if (!(msg instanceof ToolExecutionResultMessage)) {
        if (msg.text() == null || msg.text().trim().isEmpty()) continue;
      }
      cleaned.add(msg);
    }

    // 第二遍：移除孤立的 ToolExecutionResultMessage（前面没有带 tool_calls 的 AiMessage）
    List<ChatMessage> result = new ArrayList<>();
    for (int i = 0; i < cleaned.size(); i++) {
      ChatMessage msg = cleaned.get(i);
      if (msg instanceof ToolExecutionResultMessage) {
        // 检查前一条是否为带 tool_calls 的 AiMessage
        boolean hasPrecedingToolCall = false;
        for (int j = i - 1; j >= 0; j--) {
          ChatMessage prev = cleaned.get(j);
          if (prev instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
            hasPrecedingToolCall = true;
            break;
          }
          // 如果前一条是普通消息（非 AiMessage），停止搜索
          if (!(prev instanceof ToolExecutionResultMessage)) break;
        }
        if (!hasPrecedingToolCall) {
          log.debug("【消息清洗】跳过孤立的 ToolExecutionResultMessage: {}", msg.text());
          continue;
        }
      }
      result.add(msg);
    }
    return result;
  }

  /**
   * 方案 C 辅助方法：判断用户消息是否为身份类问题。
   * 覆盖所有常见的身份询问问法，避免误命中（如"你是怎么查到的"）。
   */
  private boolean isIdentityQuestion(String text) {
    if (text == null) return false;
    String t = text.trim();

    // 精确匹配：包含这些短语即可判定为身份问题
    String[] exactPhrases = {
        "你是谁", "你是啥", "你是谁呀", "你是谁啊",
        "你叫什么", "你的名字", "你叫啥", "你叫什么名字",
        "怎么称呼你", "怎么称呼您", "称呼你",
        "自我介绍", "介绍一下你", "介绍下你", "介绍你自己",
        "谁在聊天", "谁在回复", "对面是谁",
        "你是机器人吗", "你是人工吗", "你是ai吗", "你是AI吗",
        "小彦是谁", "小彦是啥", "小彦是谁啊",
        "告诉我你的名字", "你的身份", "你是什么"
    };
    for (String phrase : exactPhrases) {
      if (t.contains(phrase)) {
        log.info("【客服节点】身份检测命中短语: \"{}\", 用户输入: \"{}\"", phrase, t);
        return true;
      }
    }

    // 正则兜底：短文本（≤15字）+ 以问号结尾 + 含"你"字 → 大概率是身份问题
    if (t.length() <= 15 && (t.endsWith("？") || t.endsWith("?")) && t.contains("你")) {
      log.info("【客服节点】身份检测命中正则兜底, 用户输入: \"{}\"", t);
      return true;
    }

    return false;
  }

  /**
   * 构建智能客服 System Prompt。
   */
  private SystemMessage buildCustomerServicePrompt(String orderContext, OrderFlowState state) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("# 角色设定\n");
    prompt.append("你是一位专业的跨境电商智能供应链助手，名字叫\"小彦\"。你专业、热情、有耐心。\n");
    prompt.append("你的职责是帮助用户查询跨境订单、追踪海外仓库存以及解决物流异常。\n");
    prompt.append("你拥有工具调用能力，可以主动查询订单详情、物流轨迹、商品库存等实时数据。\n");
    prompt.append("当用户询问你的身份、名字或\"你是谁\"时，你必须礼貌地回答你叫\"小彦\"。\n\n");

    prompt.append("# 订单上下文\n");
    prompt.append(orderContext).append("\n");

    prompt.append("# 核心能力\n");
    prompt.append("1. 精准理解用户意图，覆盖10种常见场景\n");
    prompt.append("2. 主动调用工具查询实时数据（订单、物流、库存），不依赖过时信息\n");
    prompt.append("3. 根据订单当前状态，智能判断用户请求的合理性\n");
    prompt.append("4. 识别用户情绪（着急/生气/满意/疑惑），调整回复语气\n");
    prompt.append("5. 多轮对话中理解指代和省略，保持上下文连贯\n");
    prompt.append("6. 信息不完整时主动追问，避免给出错误回复\n\n");

    prompt.append("# 订单状态说明与处理策略\n");
    prompt.append("- PENDING_PAY（待支付）：用户可询问支付方式、取消订单；暂不支持退款\n");
    prompt.append("- SHIPPED（已发货）：用户常问物流进度；如要退款需先拒收包裹\n");
    prompt.append("- DELIVERED（已送达）：用户常问签收确认、售后；退款需走退货流程\n");
    prompt.append("- REFUND_PENDING（退款审批中）：告知用户正在处理，安抚等待\n");
    prompt.append("- APPROVED（已批准退款）：退款正在处理中，预计3-5个工作日到账\n");
    prompt.append("- REJECTED（已驳回退款）：解释驳回原因，提供替代方案\n");
    prompt.append("- REFUNDED（已退款）：退款已完成，可查询到账情况\n\n");

    prompt.append("# 输出格式（必须严格遵守）\n");
    prompt.append("请以 JSON 格式输出，不要包含任何其他内容：\n");
    prompt.append("{\n");
    prompt.append("  \"intent\": \"LOGISTICS|REFUND|INQUIRY|COMPLAINT|ORDER_MODIFY|PAYMENT|PRODUCT|RECOMMEND|COUPON|OTHER\",\n");
    prompt.append("  \"confidence\": 0.0-1.0,\n");
    prompt.append("  \"userEmotion\": \"calm|urgent|angry|satisfied|confused\",\n");
    prompt.append("  \"response\": \"给用户看的回复内容\"\n");
    prompt.append("}\n\n");

    prompt.append("# 意图定义\n");
    prompt.append("- LOGISTICS：用户查询物流进度、快递位置、配送时间\n");
    prompt.append("- REFUND：用户明确要求退款、退货、取消订单（需判断合理性）\n");
    prompt.append("- INQUIRY：询问订单状态、商品信息、价格等\n");
    prompt.append("- COMPLAINT：表达不满、投诉质量问题、服务问题\n");
    prompt.append("- ORDER_MODIFY：用户想修改收货地址、商品数量、商品规格等订单信息\n");
    prompt.append("- PAYMENT：支付方式咨询、支付失败、支付异常、发票问题\n");
    prompt.append("- PRODUCT：询问商品详情、规格参数、使用方法、兼容性等\n");
    prompt.append("- RECOMMEND：用户想要推荐类似商品、替代方案、搭配建议\n");
    prompt.append("- COUPON：优惠券、促销活动、折扣、满减等咨询\n");
    prompt.append("- OTHER：打招呼、感谢、闲聊等不属于以上类别\n\n");

    prompt.append("# 上下文理解规则\n");
    prompt.append("1. 当用户使用指代词（'那个订单'、'它'、'这个'、'之前说的'）时，结合对话历史推断具体指向\n");
    prompt.append("2. 记住用户在本次对话中提到的所有关键信息（商品名、问题描述、情绪变化）\n");
    prompt.append("3. 如果用户延续之前的话题，不要重复询问已经提供的信息\n");
    prompt.append("4. 如果用户意图不明确（如只说了'帮我看看'），根据上下文推断最可能的意图\n\n");

    prompt.append("# 主动追问规则\n");
    prompt.append("当信息不完整时，礼貌地追问，例如：\n");
    prompt.append("- 用户说'我要退款'但没说原因 → 追问退款原因以便处理\n");
    prompt.append("- 用户说'帮我查物流'但对话中有多个订单 → 追问具体是哪个订单\n");
    prompt.append("- 用户说'有没有更好的' → 追问想要什么类型的替代品\n\n");

    prompt.append("# 回复风格指南\n");
    prompt.append("1. 语气亲切自然，像真人客服，避免机械感\n");
    prompt.append("2. 先安抚情绪，再解决问题（用户生气时先道歉）\n");
    prompt.append("3. 信息准确，不编造数据——需要查询时主动调用工具\n");
    prompt.append("4. 复杂问题分步骤说明，让用户容易理解\n");
    prompt.append("5. 适当使用表情符号增加亲和力（如：😊、📦、✅）\n");
    prompt.append("6. 每次回复控制在200字以内，避免信息过载\n\n");

    prompt.append("# Few-shot 示例\n\n");

    prompt.append("## 物流查询\n");
    prompt.append("用户：\"我的快递到哪了？\"\n");
    prompt.append("输出：{\"intent\":\"LOGISTICS\",\"confidence\":0.98,\"userEmotion\":\"calm\",\"response\":\"好的，我帮您查一下物流进度 📦\"}\n\n");

    prompt.append("## 退款申请\n");
    prompt.append("用户：\"这个显示器有坏点，我要退货！\"\n");
    prompt.append("输出：{\"intent\":\"REFUND\",\"confidence\":0.95,\"userEmotion\":\"angry\",\"response\":\"非常抱歉给您带来不好的体验 😔 有坏点确实影响使用，我立即为您转交退款申请。\"}\n\n");

    prompt.append("## 修改订单\n");
    prompt.append("用户：\"能帮我改一下收货地址吗？\"\n");
    prompt.append("输出：{\"intent\":\"ORDER_MODIFY\",\"confidence\":0.95,\"userEmotion\":\"calm\",\"response\":\"很抱歉，订单一旦提交暂时不支持在线修改收货地址 😅 您可以联系人工客服协助处理，或者在包裹派送时联系快递员更改地址。\"}\n\n");

    prompt.append("## 支付问题\n");
    prompt.append("用户：\"支付一直失败怎么办？\"\n");
    prompt.append("输出：{\"intent\":\"PAYMENT\",\"confidence\":0.92,\"userEmotion\":\"urgent\",\"response\":\"理解您着急的心情，支付失败可能有以下原因：1️⃣ 银行卡余额不足 2️⃣ 网络不稳定 3️⃣ 支付限额。建议您换个支付方式试试，或稍后重试~\"}\n\n");

    prompt.append("## 商品咨询\n");
    prompt.append("用户：\"这个耳机的降噪效果怎么样？\"\n");
    prompt.append("输出：{\"intent\":\"PRODUCT\",\"confidence\":0.93,\"userEmotion\":\"calm\",\"response\":\"好的，我帮您查一下这款耳机的详细信息 🎧\"}\n\n");

    prompt.append("## 商品推荐\n");
    prompt.append("用户：\"有没有类似的耳机推荐？\"\n");
    prompt.append("输出：{\"intent\":\"RECOMMEND\",\"confidence\":0.90,\"userEmotion\":\"calm\",\"response\":\"好的，我帮您看看有哪些类似的耳机可以选择 🎧\"}\n\n");

    prompt.append("## 优惠券\n");
    prompt.append("用户：\"有没有什么优惠活动？\"\n");
    prompt.append("输出：{\"intent\":\"COUPON\",\"confidence\":0.88,\"userEmotion\":\"calm\",\"response\":\"目前新用户注册即享首单9折优惠 🎉 更多促销活动请关注我们的活动页面，不定期更新哦~\"}\n\n");

    prompt.append("## 指代消解\n");
    prompt.append("（对话历史中用户曾询问订单1001的物流）\n");
    prompt.append("用户：\"那个订单到哪了？\"\n");
    prompt.append("输出：{\"intent\":\"LOGISTICS\",\"confidence\":0.95,\"userEmotion\":\"calm\",\"response\":\"好的，我帮您查一下订单1001的物流进度 📦\"}\n\n");

    prompt.append("## 主动追问\n");
    prompt.append("用户：\"我要退款\"\n");
    prompt.append("输出：{\"intent\":\"REFUND\",\"confidence\":0.85,\"userEmotion\":\"calm\",\"response\":\"收到您的退款诉求 🙏 方便告诉我退款原因吗？这样我能更快帮您处理~\"}\n\n");

    prompt.append("## 打招呼\n");
    prompt.append("用户：\"谢谢\"\n");
    prompt.append("输出：{\"intent\":\"OTHER\",\"confidence\":0.99,\"userEmotion\":\"satisfied\",\"response\":\"不客气！有问题随时找我 😊\"}");

    return new SystemMessage(prompt.toString());
  }

  /**
   * 意图识别结果。
   */
  private record IntentResult(
      String intent,
      double confidence,
      String userEmotion,
      String response
  ) {}

  /**
   * 解析模型的结构化输出。
   */
  private IntentResult parseIntentResult(String rawContent, OrderFlowState state) {
    // 空内容兜底
    if (rawContent == null || rawContent.trim().isEmpty()) {
      return new IntentResult("OTHER", 0.0, "confused",
          "抱歉，我没听清楚，您能再说一遍吗？😊");
    }

    try {
      // 尝试提取 JSON
      String jsonStr = extractJson(rawContent);
      if (jsonStr != null) {
        Map<String, Object> map = objectMapper.readValue(jsonStr, Map.class);
        String intent = getString(map, "intent", "OTHER").toUpperCase();
        double confidence = getDouble(map, "confidence", 0.5);
        String emotion = getString(map, "userEmotion", "calm").toLowerCase();
        String response = getString(map, "response",
            "抱歉，我正在学习中，稍后将为您安排人工客服。");

        // 意图校验
        if (!isValidIntent(intent)) {
          intent = "OTHER";
        }

        return new IntentResult(intent, confidence, emotion, response);
      }
    } catch (Exception e) {
      log.warn("【客服节点】JSON解析失败，回退到关键词匹配，raw={}", rawContent);
    }

    // 回退：关键词匹配
    return fallbackIntentParse(rawContent);
  }

  /**
   * 从文本中提取 JSON 片段。
   */
  private String extractJson(String text) {
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start >= 0 && end > start) {
      return text.substring(start, end + 1);
    }
    return null;
  }

  /**
   * 关键词回退解析。
   */
  private IntentResult fallbackIntentParse(String text) {
    String upper = text.toUpperCase();
    if (upper.contains("INTENT:LOGISTICS") || upper.contains("物流") || upper.contains("快递")
        || upper.contains("配送") || upper.contains("到哪")) {
      return new IntentResult("LOGISTICS", 0.7, "calm", text);
    }
    if (upper.contains("INTENT:REFUND") || upper.contains("退款") || upper.contains("退货")
        || upper.contains("退钱") || upper.contains("取消订单")) {
      return new IntentResult("REFUND", 0.7, "calm", text);
    }
    if (upper.contains("INTENT:ORDER_MODIFY") || upper.contains("修改地址") || upper.contains("改地址")
        || upper.contains("修改数量") || upper.contains("改数量")) {
      return new IntentResult("ORDER_MODIFY", 0.7, "calm", text);
    }
    if (upper.contains("INTENT:PAYMENT") || upper.contains("支付") || upper.contains("付款")
        || upper.contains("发票")) {
      return new IntentResult("PAYMENT", 0.7, "calm", text);
    }
    if (upper.contains("INTENT:PRODUCT") || upper.contains("商品详情") || upper.contains("规格")
        || upper.contains("参数") || upper.contains("使用方法")) {
      return new IntentResult("PRODUCT", 0.7, "calm", text);
    }
    if (upper.contains("INTENT:RECOMMEND") || upper.contains("推荐") || upper.contains("类似")
        || upper.contains("替代") || upper.contains("还有没有")) {
      return new IntentResult("RECOMMEND", 0.7, "calm", text);
    }
    if (upper.contains("INTENT:COUPON") || upper.contains("优惠") || upper.contains("折扣")
        || upper.contains("促销") || upper.contains("满减")) {
      return new IntentResult("COUPON", 0.7, "calm", text);
    }
    if (upper.contains("INTENT:COMPLAINT") || upper.contains("投诉") || upper.contains("不满")
        || upper.contains("差评") || upper.contains("质量")) {
      return new IntentResult("COMPLAINT", 0.7, "calm", text);
    }
    return new IntentResult("OTHER", 0.5, "calm", text);
  }

  private boolean isValidIntent(String intent) {
    return List.of("LOGISTICS", "REFUND", "INQUIRY", "COMPLAINT",
        "ORDER_MODIFY", "PAYMENT", "PRODUCT", "RECOMMEND", "COUPON", "OTHER").contains(intent);
  }

  private String getString(Map<String, Object> map, String key, String defaultVal) {
    Object val = map.get(key);
    return val instanceof String ? (String) val : defaultVal;
  }

  private double getDouble(Map<String, Object> map, String key, double defaultVal) {
    Object val = map.get(key);
    if (val instanceof Number) {
      return ((Number) val).doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(val));
    } catch (Exception e) {
      return defaultVal;
    }
  }

  /**
   * 处理物流查询意图。
   */
  private void handleLogisticsIntent(OrderFlowState state, List<ChatMessage> messages, IntentResult result) {
    log.info("【客服节点】识别到物流查询意图，情绪={}, orderId={}", result.userEmotion, state.getOrderId());

    Order order = orderService.getOrderDetail(state.getOrderId());
    String orderStatus = order != null ? order.getStatus() : "";

    // 订单未发货时的智能提示
    if ("PENDING_PAY".equals(orderStatus)) {
      messages.add(new AiMessage("📦 您的订单还未付款哦，付款后我们会尽快安排发货。需要我帮您看看支付方式吗？"));
      state.setCurrentDepartment("END");
      return;
    }

    Logistics logistics = logisticsService.getLogisticsInfo(state.getOrderId());
    if (logistics != null) {
      // 根据物流状态生成个性化回复
      String statusDesc = switch (logistics.getStatus()) {
        case "IN_TRANSIT" -> "运输中";
        case "DELIVERED" -> "已送达";
        case "PENDING" -> "待发货";
        default -> logistics.getStatus();
      };

      String emotionPrefix = switch (result.userEmotion) {
        case "urgent" -> "理解您着急的心情，";
        case "angry" -> "非常抱歉让您久等了，";
        default -> "";
      };

      String reply = String.format(
          "%s为您查询到最新物流信息：\n📋 物流单号：%s\n📍 当前状态：%s\n🌏 最新位置：%s\n\n预计很快送达，请保持手机畅通哦 😊",
          emotionPrefix, logistics.getTrackingNumber(), statusDesc, logistics.getLastLocation()
      );
      messages.add(new AiMessage(reply));
    } else {
      messages.add(new AiMessage("📦 该订单暂无物流信息，可能还在打包准备中。一般付款后24小时内会发货，请您耐心等待~"));
    }
    state.setCurrentDepartment("END");
  }

  /**
   * 处理退款意图。
   */
  private void handleRefundIntent(OrderFlowState state, List<ChatMessage> messages, IntentResult result) {
    log.info("【客服节点】识别到退款意图，情绪={}, orderId={}", result.userEmotion, state.getOrderId());

    Order order = orderService.getOrderDetail(state.getOrderId());
    String orderStatus = order != null ? order.getStatus() : "";

    // 根据订单状态智能判断退款合理性
    String transitionMsg = switch (orderStatus) {
      case "PENDING_PAY" ->
          "您的订单还未付款，可以直接取消哦，无需走退款流程~ 需要我帮您操作吗？";
      case "DELIVERED" ->
          "订单已送达，退款需要先办理退货。我来为您转交售后部门核实情况。";
      case "REFUND_PENDING" ->
          "您的退款申请正在审批中，请耐心等待主管审核结果，一般1-2个工作日会有反馈。";
      case "APPROVED" ->
          "退款已批准，正在处理中，预计3-5个工作日到账，请留意您的支付账户。";
      case "REJECTED" ->
          "您的退款申请已被驳回，我来为您转交售后，看看是否有其他解决方案。";
      case "REFUNDED" ->
          "退款已完成，款项已原路退回，请检查您的支付账户到账情况。";
      default -> "已收到您的退款诉求，正在为您核实订单情况，请稍候。";
    };

    // 如果已在退款流程中，直接结束不转交
    if (List.of("REFUND_PENDING", "APPROVED", "REFUNDED").contains(orderStatus)) {
      messages.add(new AiMessage(transitionMsg));
      state.setCurrentDepartment("END");
      return;
    }

    // 用户生气时先安抚
    if ("angry".equals(result.userEmotion)) {
      transitionMsg = "非常抱歉给您带来不好的体验 😔 " + transitionMsg;
    }

    messages.add(new AiMessage(transitionMsg));

    // 只有真正需要退款的订单才转交库存部门
    if (List.of("SHIPPED", "DELIVERED").contains(orderStatus) || orderStatus.isEmpty()) {
      state.setCurrentDepartment("INVENTORY");
    } else {
      state.setCurrentDepartment("END");
    }
  }

  /**
   * 处理商品咨询/推荐意图。
   *
   * <p>客服节点已通过 Tool Calling 获取了商品信息，直接将模型的回复返回给用户。</p>
   */
  private void handleProductIntent(OrderFlowState state, List<ChatMessage> messages, IntentResult result) {
    log.info("【客服节点】识别到商品意图: {}, orderId={}", result.intent, state.getOrderId());
    messages.add(new AiMessage(result.response));
    state.setCurrentDepartment("END");
  }

  /**
   * 对话摘要压缩。
   *
   * <p>当对话轮数超过阈值时，对早期对话生成摘要，保留最近4轮原文。</p>
   *
   * @param messages 完整对话历史
   * @return 压缩后的消息列表（摘要 + 最近4轮）
   */
  private List<ChatMessage> summarizeHistory(List<ChatMessage> messages) {
    int keepRecent = 4;
    int splitIndex = messages.size() - keepRecent;

    // 提取需要摘要的早期对话
    List<ChatMessage> earlyMessages = messages.subList(0, splitIndex);
    StringBuilder summaryPrompt = new StringBuilder();
    summaryPrompt.append("请将以下客服对话压缩为一段简洁的摘要，保留关键信息（用户诉求、已执行操作、用户情绪变化）：\n\n");
    for (ChatMessage msg : earlyMessages) {
      if (msg instanceof UserMessage) {
        summaryPrompt.append("用户：").append(msg.text()).append("\n");
      } else if (msg instanceof AiMessage) {
        summaryPrompt.append("客服：").append(msg.text()).append("\n");
      }
    }
    summaryPrompt.append("\n请用一段话概括以上对话内容，不超过100字。");

    try {
      Response<AiMessage> summaryResponse = chatLanguageModel.generate(
          new UserMessage(summaryPrompt.toString()));
      String summaryText = (summaryResponse.content() != null && summaryResponse.content().text() != null)
          ? summaryResponse.content().text() : "（对话摘要生成失败）";
      log.info("【客服节点】对话摘要: {}", summaryText);

      // 构建压缩后的消息列表：摘要 + 最近4轮
      List<ChatMessage> compressed = new ArrayList<>();
      compressed.add(new SystemMessage("【历史对话摘要】" + summaryText));
      compressed.addAll(messages.subList(splitIndex, messages.size()));
      return compressed;
    } catch (Exception e) {
      log.warn("【客服节点】对话摘要生成失败，保留完整历史", e);
      return messages;
    }
  }

  /**
   * 构建订单上下文信息，用于注入客服 System Prompt。
   */
  private String buildOrderContext(Long orderId) {
    StringBuilder ctx = new StringBuilder();

    // 订单基本信息
    try {
      Order order = orderService.getOrderDetail(orderId);
      if (order != null) {
        ctx.append("【订单信息】\n");
        ctx.append("- 订单号：").append(order.getId()).append("\n");
        ctx.append("- 用户ID：").append(order.getUserId()).append("\n");
        ctx.append("- 订单状态：").append(order.getStatus()).append("\n");
        ctx.append("- 订单金额：¥").append(order.getTotalAmount().toPlainString()).append("\n");
        ctx.append("- 下单时间：").append(order.getCreateTime()).append("\n");

        // 添加状态可读说明
        String statusDesc = switch (order.getStatus()) {
          case "PENDING_PAY" -> "待支付";
          case "SHIPPED" -> "已发货";
          case "DELIVERED" -> "已送达";
          case "REFUND_PENDING" -> "退款审批中";
          case "APPROVED" -> "退款已批准";
          case "REJECTED" -> "退款已驳回";
          case "REFUNDED" -> "已退款";
          default -> order.getStatus();
        };
        ctx.append("- 状态说明：").append(statusDesc).append("\n");
      } else {
        ctx.append("【订单信息】订单不存在\n");
      }
    } catch (Exception e) {
      log.warn("【客服节点】加载订单信息失败，orderId={}", orderId, e);
      ctx.append("【订单信息】加载失败\n");
    }

    // 商品明细
    try {
      List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
      if (!items.isEmpty()) {
        ctx.append("\n【商品明细】\n");
        for (OrderItem item : items) {
          ctx.append("- 商品ID: ").append(item.getProductId())
              .append("，数量: ").append(item.getQuantity())
              .append("，单价: ¥").append(item.getUnitPrice().toPlainString()).append("\n");
        }
      }
    } catch (Exception e) {
      log.warn("【客服节点】加载商品明细失败，orderId={}", orderId, e);
    }

    // 物流信息
    try {
      Logistics logistics = logisticsService.getLogisticsInfo(orderId);
      if (logistics != null) {
        ctx.append("\n【物流信息】\n");
        ctx.append("- 物流单号：").append(logistics.getTrackingNumber()).append("\n");
        ctx.append("- 物流状态：").append(logistics.getStatus()).append("\n");
        ctx.append("- 最新位置：").append(logistics.getLastLocation()).append("\n");
      } else {
        ctx.append("\n【物流信息】暂无物流信息\n");
      }
    } catch (Exception e) {
      log.debug("【客服节点】该订单暂无物流信息，orderId={}", orderId);
      ctx.append("\n【物流信息】暂无物流信息\n");
    }

    return ctx.toString();
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
    // ===== 熔断检测：递增迭代计数器，超过阈值则中断 =====
    int currentCount = state.incrementToolCallCount();
    log.info("【库存节点】开始处理，orderId={}, toolCallCount={}/{}", state.getOrderId(), currentCount, MAX_LOOPS);
    if (currentCount > MAX_LOOPS) {
      log.warn("【熔断】库存节点迭代次数 {} 超过安全阈值 {}，触发熔断，orderId={}",
          currentCount, MAX_LOOPS, state.getOrderId());
      throw new AgentMaxIterationException(state.getOrderId(), currentCount, MAX_LOOPS);
    }

    List<ChatMessage> messages = sanitizeMessages(state.getMessages());

    // 收集库存节点可用的工具规范
    List<ToolSpecification> toolSpecs = new ArrayList<>();
    toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(inventoryService));
    toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(refundApprovalService));

    SystemMessage systemMsg = new SystemMessage(
        "你是资深供应链专家\"小仓\"，负责核实库存并处理退款审批。\n\n"
            + "# 工作原则\n"
            + "1. 先核查每个商品的库存情况\n"
            + "2. 如果库存充足，建议换货而非退款\n"
            + "3. 只有确认彻底缺货且无法补发时，才提交退款审批\n"
            + "4. 退款审批 reason 必须详细说明缺货原因和影响\n\n"
            + "# 回复风格\n"
            + "- 专业严谨，数据说话\n"
            + "- 结论清晰，让用户明白处理结果\n"
            + "- 适当解释供应链流程，增加透明度"
    );

    List<ChatMessage> promptMessages = new ArrayList<>();
    promptMessages.add(systemMsg);
    promptMessages.addAll(messages);

    Response<AiMessage> response = chatLanguageModel.generate(promptMessages, toolSpecs);
    AiMessage aiMessage = response.content();

    // 兜底：模型返回 null 或空消息
    if (aiMessage == null) {
      log.warn("【库存节点】模型返回空消息，orderId={}", state.getOrderId());
      messages.add(new AiMessage("抱歉，库存分析暂时无法完成，请稍后再试。我们的工程师正在排查。"));
      state.setMessages(messages);
      state.setCurrentDepartment("END");
      return state;
    }

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
      if (aiMessage == null) {
        log.warn("【库存节点】工具调用后模型返回空消息，orderId={}", state.getOrderId());
        break;
      }
    }

    if (aiMessage != null) {
      messages.add(aiMessage);
    }
    state.setMessages(messages);

    // 仅当 submitRefundApproval 工具被实际调用时才进入主管节点，
    // 避免 LLM 回复中偶然提到"退款审批"等关键词导致误路由
    boolean refundInitiated = messages.stream()
        .anyMatch(m -> m instanceof ToolExecutionResultMessage
            && ((ToolExecutionResultMessage) m).toolName().equals("submitRefundApproval"));

    if (refundInitiated) {
      log.info("【库存节点】submitRefundApproval 已调用，进入退款审批流程，orderId={}", state.getOrderId());
      state.setCurrentDepartment("SUPERVISOR");
    } else {
      log.info("【库存节点】库存检查完毕，未触发退款审批，流程结束，orderId={}", state.getOrderId());
      state.setCurrentDepartment("END");
    }

    return state;
  }

  /**
   * 【主管节点】虚拟主管审批节点。
   *
   * <p>检查 {@code contextData} 中是否包含管理员审批结果 {@code managerDecision}。
   * <ul>
   *   <li>若审批通过（APPROVED），调用 {@link RefundApprovalService#approveRefund}
   *       将订单状态修改为已同意(APPROVED)；</li>
   *   <li>若审批驳回（REJECTED），调用 {@link RefundApprovalService#rejectRefund}
   *       将订单状态修改为不同意(REJECTED)；</li>
   *   <li>若尚无审批数据，将 {@code requireHumanApproval} 设为 {@code true}
   *       触发挂起（由 {@code interruptBefore} 机制暂停）。</li>
   * </ul>
   *
   * @param state 当前全局状态
   * @return 更新后的状态
   */
  public OrderFlowState supervisorNode(OrderFlowState state) {
    log.info("【主管节点】开始处理，orderId={}, currentDepartment={}, contextData={}",
        state.getOrderId(), state.getCurrentDepartment(), state.getContextData());

    Map<String, Object> context = state.getContextData();
    Object decision = context.get("managerDecision");
    log.info("【主管节点】审批决定: decision={}", decision);

    // 读取消息列表用于追加结果消息
    List<ChatMessage> messages = new ArrayList<>(state.getMessages());

    if ("APPROVED".equals(decision)) {
      String comment = (String) context.get("managerComment");
      log.info("【主管节点】管理员已审批通过，orderId={}, comment={}", state.getOrderId(), comment);

      // 合并事务：审批通过 + 执行退款在同一事务中，避免状态卡在 APPROVED
      boolean refundOk = refundApprovalService.approveAndExecuteRefund(state.getOrderId(), comment);
      log.info("【主管节点】退款执行结果: orderId={}, success={}", state.getOrderId(), refundOk);

      messages.add(new AiMessage("【审批结果】订单 #" + state.getOrderId()
          + " 已由主管审批通过，系统已执行退款，订单状态已更新为：已退款(REFUNDED)，库存已回滚。工作流已结束。"));
      state.setMessages(messages);

      state.setCurrentDepartment("FINISH");
      state.setRequireHumanApproval(false);

    } else if ("REJECTED".equals(decision)) {
      String comment = (String) context.get("managerComment");
      log.info("【主管节点】管理员已驳回退款申请，orderId={}, comment={}", state.getOrderId(), comment);

      // 驳回退款，审批日志记录 REJECTED，订单状态恢复为 SHIPPED
      refundApprovalService.rejectRefundAndRestoreOrder(state.getOrderId(), comment);

      messages.add(new AiMessage("【审批结果】订单 #" + state.getOrderId()
          + " 退款申请已被主管驳回，订单状态已恢复为：已发货(SHIPPED)。工作流已结束。"));
      state.setMessages(messages);

      state.setCurrentDepartment("FINISH");
      state.setRequireHumanApproval(false);

    } else {
      log.info("【主管节点】暂无审批结果，挂起等待人工审核，orderId={}", state.getOrderId());
      state.setRequireHumanApproval(true);
      // currentDepartment 保持 SUPERVISOR，恢复后继续进入本节点
    }

    return state;
  }

  /**
   * 【降级节点】大模型幻觉熔断后的兜底处理。
   *
   * <p>当 {@link AgentMaxIterationException} 被触发时，工作流自动路由到此节点。
   * 不打扰用户，输出平稳的兜底话术，并将流程标记为结束。</p>
   *
   * @param state 当前全局状态
   * @return 更新后的状态（含兜底消息，流程已终止）
   */
  public OrderFlowState fallbackNode(OrderFlowState state) {
    log.warn("【降级节点】进入熔断降级处理，orderId={}, toolCallCount={}",
        state.getOrderId(), state.getToolCallCount());

    List<ChatMessage> messages = new ArrayList<>(state.getMessages());
    messages.add(new AiMessage(FALLBACK_REPLY));
    state.setMessages(messages);

    // 标记流程结束 + 需要人工介入
    state.setCurrentDepartment("FINISH");
    state.setRequireHumanApproval(true);

    log.info("【降级节点】兜底话术已注入，流程终止，orderId={}", state.getOrderId());
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
    log.info("【路由】currentDepartment={}, toolCallCount={}", dept, state.getToolCallCount());
    return switch (dept) {
      case "INVENTORY" -> "inventoryNode";
      case "SUPERVISOR" -> "supervisorNode";
      case "FALLBACK" -> "fallbackNode";
      case "FINISH" -> END;
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
    schema.put("toolCallCount", Channels.<Integer>base(() -> 0));

    // 使用 JacksonStateSerializer 替代默认的 ObjectStreamStateSerializer，
    // 解决 ChatMessage 未实现 Serializable 导致的深拷贝失败问题。
    var stateSerializer = new org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer<>(OrderFlowState::new, objectMapper) {};
    StateGraph<OrderFlowState> graph = new StateGraph<>(stateSerializer);

    // 注册核心节点 + 降级节点
    graph.addNode("customerServiceNode",
        AsyncNodeAction.node_async(state -> customerServiceNode(state).data()));
    graph.addNode("inventoryNode",
        AsyncNodeAction.node_async(state -> inventoryNode(state).data()));
    graph.addNode("supervisorNode",
        AsyncNodeAction.node_async(state -> supervisorNode(state).data()));
    graph.addNode("fallbackNode",
        AsyncNodeAction.node_async(state -> fallbackNode(state).data()));

    // 条件边：所有节点共享同一个路由逻辑（含降级路由）
    Map<String, String> routeMapping = Map.of(
        "customerServiceNode", "customerServiceNode",
        "inventoryNode", "inventoryNode",
        "supervisorNode", "supervisorNode",
        "fallbackNode", "fallbackNode",
        END, END
    );

    graph.addConditionalEdges("customerServiceNode",
        AsyncEdgeAction.edge_async(this::routeNextStep), routeMapping);
    graph.addConditionalEdges("inventoryNode",
        AsyncEdgeAction.edge_async(this::routeNextStep), routeMapping);
    graph.addConditionalEdges("supervisorNode",
        AsyncEdgeAction.edge_async(this::routeNextStep), routeMapping);
    // fallbackNode 无条件走向 END
    graph.addEdge("fallbackNode", END);

    // 入口
    graph.addEdge(START, "customerServiceNode");

    // 编译配置：在进入主管节点前中断，使用数据库 CheckpointSaver 以支持持久化与断点续传
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
        case "getOrderDetail" -> {
          Long orderId = ((Number) args.get("orderId")).longValue();
          Order order = orderService.getOrderDetail(orderId);
          if (order == null) {
            yield "订单不存在";
          }
          yield String.format("订单号=%d, 状态=%s, 金额=%s, 下单时间=%s",
              order.getId(), order.getStatus(), order.getTotalAmount().toPlainString(), order.getCreateTime());
        }
        case "getLogisticsInfo" -> {
          Long orderId = ((Number) args.get("orderId")).longValue();
          Logistics logistics = logisticsService.getLogisticsInfo(orderId);
          if (logistics == null) {
            yield "暂无物流信息";
          }
          yield String.format("物流单号=%s, 状态=%s, 最新位置=%s",
              logistics.getTrackingNumber(), logistics.getStatus(), logistics.getLastLocation());
        }
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
