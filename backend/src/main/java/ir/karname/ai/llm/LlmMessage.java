package ir.karname.ai.llm;

import java.util.List;

/** One message of a conversation with a model. */
public sealed interface LlmMessage {

    List<Part> parts();

    record User(List<Part> parts) implements LlmMessage {
    }

    /**
     * A model's reply. {@code nativeMessage} keeps the provider's own form of it (with reasoning
     * blocks and their signatures) so it can be sent back exactly as received.
     */
    record Assistant(List<Part> parts, Native nativeMessage) implements LlmMessage {
    }

    /** A message in a provider's wire format: {@code format} names the adapter that wrote it. */
    record Native(String format, String json) {
    }
}
