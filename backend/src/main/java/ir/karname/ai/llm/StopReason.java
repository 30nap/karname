package ir.karname.ai.llm;

public enum StopReason {
    /** The model finished its answer. */
    END,
    /** The model is waiting for the results of the tool calls it made. */
    TOOL_USE,
    /** The reply was cut off by the output limit. */
    MAX_TOKENS,
    /** The provider declined the request (safety classifiers or the model itself). */
    REFUSAL,
    OTHER
}
