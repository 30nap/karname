package ir.karname.ai.llm;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * JSON replies from models that cannot be held to a schema by the provider: the prompt asks for
 * JSON, and the reply is read leniently (code fences or a sentence around the object are ignored).
 */
public final class StructuredReply {

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private StructuredReply() {
    }

    /** Appended to the system prompt; deterministic, so the prompt stays cacheable. */
    public static String instructions(StructuredOutput output) {
        return "\n\n# Output format\nReply with one JSON object and nothing else: no explanation and no Markdown code fences. "
                + "It must conform to this JSON Schema:\n" + JSON.writeValueAsString(output.schema().toMap());
    }

    /** The JSON object in {@code text}, if there is one. */
    public static Optional<JsonNode> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.trim();
        Optional<JsonNode> whole = read(t);
        if (whole.isPresent()) {
            return whole;
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        return start >= 0 && end > start ? read(t.substring(start, end + 1)) : Optional.empty();
    }

    private static Optional<JsonNode> read(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}
