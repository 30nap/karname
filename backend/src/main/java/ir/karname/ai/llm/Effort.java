package ir.karname.ai.llm;

/** How much the model may think before answering; trades quality for latency and cost. */
public enum Effort {
    LOW, MEDIUM, HIGH, XHIGH, MAX
}
