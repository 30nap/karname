package ir.karname.ai.llm;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A piece of a message, independent of any provider's wire format. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Part.Text.class, name = "text"),
        @JsonSubTypes.Type(value = Part.Context.class, name = "context"),
        @JsonSubTypes.Type(value = Part.ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = Part.ToolResult.class, name = "tool_result")})
public sealed interface Part {

    /** What the user typed, or what the model answered. */
    record Text(String text) implements Part {
    }

    /**
     * Facts the application adds to a user turn (today's date, the user's accounts…). Sent to the
     * model inside a {@code <context>} tag, never shown in the conversation.
     */
    record Context(String text) implements Part {
    }

    /** The model asking to run a tool; {@code input} is a JSON object. */
    record ToolCall(String id, String name, String input) implements Part {
    }

    /**
     * The answer to a {@link ToolCall}. {@code data} is for the user interface only (e.g. proposed
     * transactions) and is never sent to the model.
     */
    record ToolResult(String callId, String content, boolean error, String data) implements Part {
    }
}
