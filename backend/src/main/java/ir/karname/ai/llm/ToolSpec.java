package ir.karname.ai.llm;

/** A tool the model may call. */
public record ToolSpec(String name, String description, JsonSchema input) {
}
