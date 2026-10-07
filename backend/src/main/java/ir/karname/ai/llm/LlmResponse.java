package ir.karname.ai.llm;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A model's reply: text and tool calls in order, the provider's own form of the message for
 * replaying it, and why the model stopped.
 *
 * @param refusalCategory the provider's reason for a {@link StopReason#REFUSAL}, when given
 * @param model the model that actually answered (a provider may fall back to another)
 */
public record LlmResponse(List<Part> parts, LlmMessage.Native nativeMessage, StopReason stopReason, String refusalCategory,
        LlmUsage usage, String model) {

    public LlmResponse {
        parts = List.copyOf(parts);
    }

    public String text() {
        return parts.stream().filter(p -> p instanceof Part.Text).map(p -> ((Part.Text) p).text()).collect(Collectors.joining());
    }

    public List<Part.ToolCall> toolCalls() {
        return parts.stream().filter(p -> p instanceof Part.ToolCall).map(p -> (Part.ToolCall) p).toList();
    }

    public LlmMessage.Assistant toMessage() {
        return new LlmMessage.Assistant(parts, nativeMessage);
    }
}
