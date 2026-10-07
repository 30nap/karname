package ir.karname.ai.chat;

import ir.karname.ai.chat.ChatService.Summary;
import ir.karname.ai.chat.ChatService.Turn;
import ir.karname.ai.chat.ChatService.View;
import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.web.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/v1/ai")
public class ChatController {

    private static final Duration STREAM_TIMEOUT = ChatService.TURN_DEADLINE.plusMinutes(1);

    private final ChatService chat;
    private final JsonMapper json;
    private final ExecutorService executor;

    public ChatController(ChatService chat, JsonMapper json, @Qualifier("aiExecutor") ExecutorService executor) {
        this.chat = chat;
        this.json = json;
        this.executor = executor;
    }

    public record ChatRequest(Long conversationId, String text) {
    }

    public record RenameRequest(String title) {
    }

    /**
     * Sends a message and streams the reply as Server-Sent Events: {@code start}, {@code text}
     * fragments, {@code tool} progress, {@code drafts}, {@code notice}, then {@code done} or
     * {@code error}. The reply is stored even if the browser disconnects meanwhile.
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(@AuthenticationPrincipal KarnamePrincipal user, @RequestBody ChatRequest request) {
        Turn turn = chat.prepare(user.id(), request == null ? null : request.conversationId(), request == null ? null : request.text());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        SseSink sink = new SseSink(emitter, json);
        try {
            executor.execute(() -> chat.run(turn, sink));
        } catch (RejectedExecutionException e) {
            chat.abandon(turn);
            throw ApiException.unavailable("error.internal");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                // nginx must pass events through as they come
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    @GetMapping("/conversations")
    public List<Summary> list(@AuthenticationPrincipal KarnamePrincipal user) {
        return chat.list(user.id());
    }

    @GetMapping("/conversations/{id}")
    public View get(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        return chat.get(user.id(), id);
    }

    @PatchMapping("/conversations/{id}")
    public Summary rename(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id, @RequestBody RenameRequest request) {
        return chat.rename(user.id(), id, request == null ? null : request.title());
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        chat.delete(user.id(), id);
    }

    @PostMapping("/conversations/{id}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@AuthenticationPrincipal KarnamePrincipal user, @PathVariable long id) {
        chat.stop(user.id(), id);
    }
}
