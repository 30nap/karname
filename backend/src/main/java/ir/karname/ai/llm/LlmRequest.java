package ir.karname.ai.llm;

import java.util.List;

/**
 * One call to a model. {@code system} and {@code tools} must stay identical for the whole of a
 * conversation: providers cache the prompt by its exact bytes, and some bind the model's reasoning
 * to them.
 *
 * @param output when set, the reply is one JSON object conforming to this schema
 * @param effort how much the model may think; ignored by models without the setting
 * @param maxTokens output limit, including any reasoning
 */
public record LlmRequest(String model, String system, List<LlmMessage> messages, List<ToolSpec> tools, StructuredOutput output,
        Effort effort, int maxTokens) {

    public LlmRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
