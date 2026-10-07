package ir.karname.ai.llm;

/** Asks for the reply as one JSON object conforming to {@code schema}. */
public record StructuredOutput(String name, JsonSchema schema) {
}
