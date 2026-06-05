package com.shop.agent.dispatch.domain.agent.exception;

/**
 * 大模型工作流熔断异常。
 *
 * <p>当 LangGraph 工作流中 Tool 调用或 LLM 节点执行次数超过安全阈值时抛出，
 * 表明大模型可能陷入了幻觉死循环或工具滥用。触发后由 Fallback Node 接管，
 * 输出平稳的兜底话术并终止流程。</p>
 */
public class AgentMaxIterationException extends RuntimeException {

  private final long orderId;
  private final int currentCount;
  private final int maxLoops;

  public AgentMaxIterationException(long orderId, int currentCount, int maxLoops) {
    super(String.format(
        "订单 #%d 的 AI 工作流触发熔断：当前迭代次数 %d 超过安全阈值 %d，疑似大模型幻觉死循环",
        orderId, currentCount, maxLoops));
    this.orderId = orderId;
    this.currentCount = currentCount;
    this.maxLoops = maxLoops;
  }

  public long getOrderId() {
    return orderId;
  }

  public int getCurrentCount() {
    return currentCount;
  }

  public int getMaxLoops() {
    return maxLoops;
  }
}
