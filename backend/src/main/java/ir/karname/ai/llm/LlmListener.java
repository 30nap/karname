package ir.karname.ai.llm;

/** Receives a reply while it is generated. */
public interface LlmListener {

    LlmListener NONE = new LlmListener() {
    };

    /** A fragment of the answer's text. */
    default void onText(String delta) {
    }

    /** Checked while streaming; when true the request is abandoned with {@link LlmException.Kind#CANCELLED}. */
    default boolean cancelled() {
        return false;
    }

    /** The open stream, which closing aborts: lets a stop request end a long wait at once. */
    default void streaming(AutoCloseable stream) {
    }
}
