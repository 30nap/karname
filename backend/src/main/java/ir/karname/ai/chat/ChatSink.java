package ir.karname.ai.chat;

/**
 * Where a running turn reports progress: text as it is written, tools as they run, drafts, and
 * how it ended. Implementations must not throw; a client that went away just stops listening.
 */
public interface ChatSink {

    void send(String event, Object data);

    default void close() {
    }
}
