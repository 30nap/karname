package ir.karname.ai.chat;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** Server-Sent Events to the browser; once the browser goes away, events are dropped and the turn carries on. */
final class SseSink implements ChatSink {

    private final SseEmitter emitter;
    private final JsonMapper json;
    private boolean open = true;

    SseSink(SseEmitter emitter, JsonMapper json) {
        this.emitter = emitter;
        this.json = json;
        emitter.onTimeout(this::disconnected);
        emitter.onError(e -> disconnected());
    }

    @Override
    public synchronized void send(String event, Object data) {
        if (!open) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(data)));
        } catch (IOException | IllegalStateException e) {
            open = false;
        }
    }

    @Override
    public synchronized void close() {
        if (open) {
            open = false;
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // already completed
            }
        }
    }

    private synchronized void disconnected() {
        open = false;
    }
}
