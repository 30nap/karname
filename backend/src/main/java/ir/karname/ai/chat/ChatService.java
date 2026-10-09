package ir.karname.ai.chat;

import ir.karname.ai.AiAccess;
import ir.karname.ai.capture.DraftBuilder.Draft;
import ir.karname.ai.capture.DraftView;
import ir.karname.ai.chat.ConversationMessage.Role;
import ir.karname.ai.llm.Effort;
import ir.karname.ai.llm.LlmClient;
import ir.karname.ai.llm.LlmException;
import ir.karname.ai.llm.LlmListener;
import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.LlmResponse;
import ir.karname.ai.llm.LlmUsage;
import ir.karname.ai.llm.Part;
import ir.karname.ai.llm.StopReason;
import ir.karname.ai.llm.ToolSpec;
import ir.karname.ai.provider.AiProviderService;
import ir.karname.ai.provider.AiProviderService.ResolvedRoute;
import ir.karname.ai.provider.AiTask;
import ir.karname.ai.tools.FinanceTools;
import ir.karname.ai.tools.FinanceTools.ToolContext;
import ir.karname.ai.usage.AiUsageService;
import ir.karname.ai.usage.AiUsageService.Outcome;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.web.ApiException;
import ir.karname.common.web.Messages;
import ir.karname.user.UserService;
import ir.karname.user.UserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversations with the assistant. A turn runs the agent loop: the model answers or calls tools,
 * tool results go back, until it answers (at most {@link #MAX_ROUNDS} rounds and
 * {@link #TURN_DEADLINE}). Messages are stored as they happen and never edited, so each request
 * replays the conversation exactly as the provider produced it. A turn that fails is excluded from
 * later requests and kept with its error for the user to see.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    public static final int MAX_MESSAGE = 4000;
    static final int MAX_TURNS = 40;
    static final int MAX_ROUNDS = 10;
    static final int MAX_TOKENS = 16_000;
    static final Duration TURN_DEADLINE = Duration.ofMinutes(4);
    private static final int TITLE_LENGTH = 60;
    private static final TypeReference<List<Part>> PARTS = new TypeReference<>() {
    };
    private static final TypeReference<List<Draft>> DRAFTS = new TypeReference<>() {
    };

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final AiProviderService providers;
    private final AiAccess access;
    private final AiUsageService usage;
    private final FinanceTools tools;
    private final UserService users;
    private final Messages text;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final KarnameProperties properties;
    private final Clock clock;
    /** The turn each user has running: one at a time. */
    private final Map<Long, Running> running = new ConcurrentHashMap<>();

    public ChatService(ConversationRepository conversations, ConversationMessageRepository messages, AiProviderService providers,
            AiAccess access, AiUsageService usage, FinanceTools tools, UserService users, Messages text, JsonMapper json, JdbcClient jdbc,
            TransactionTemplate transactions, KarnameProperties properties, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.providers = providers;
        this.access = access;
        this.usage = usage;
        this.tools = tools;
        this.users = users;
        this.text = text;
        this.json = json;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /** A running turn, which a stop request can end early. */
    static final class Running {

        final long conversationId;
        volatile boolean stopped;
        private volatile AutoCloseable stream;

        Running(long conversationId) {
            this.conversationId = conversationId;
        }

        void stream(AutoCloseable stream) {
            this.stream = stream;
            if (stopped) {
                closeStream();
            }
        }

        void stop() {
            stopped = true;
            closeStream();
        }

        private void closeStream() {
            AutoCloseable s = stream;
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // already closed
                }
            }
        }
    }

    /** A checked turn, ready to run; its conversation exists. */
    public record Turn(long userId, long conversationId, String title, boolean created, String text, LlmClient client, String model,
            Effort effort, Long providerId, String providerName, Running handle) {
    }

    public record Summary(long id, String title, String model, String providerName, boolean available, int turns, Instant updatedAt) {
    }

    /**
     * One thing to show: {@code user} and {@code assistant} text, a {@code tool} call (with its
     * status), proposed {@code drafts}, or the {@code error} that ended a turn.
     */
    public record Item(String kind, int turn, String text, String toolId, String tool, String label, String status,
            List<DraftView> drafts) {

        static Item text(String kind, int turn, String text) {
            return new Item(kind, turn, text, null, null, null, null, null);
        }
    }

    public record View(long id, String title, String model, String providerName, boolean available, boolean full, boolean running,
            List<Item> items) {
    }

    // ---------------------------------------------------------------- turns

    /**
     * Checks everything that can be refused before streaming starts (so it is an ordinary HTTP
     * error), creates the conversation if needed, and claims the user's single running turn.
     */
    public Turn prepare(long userId, Long conversationId, String message) {
        String clean = message == null ? "" : message.strip();
        if (clean.isEmpty() || clean.length() > MAX_MESSAGE) {
            throw ApiException.badRequest("ai.invalidMessage", MAX_MESSAGE);
        }
        access.requireEnabled(userId);
        Conversation conversation = null;
        LlmClient client;
        String model;
        Effort effort;
        Long providerId;
        Optional<ResolvedRoute> route = providers.resolve(AiTask.CHAT);
        if (conversationId == null) {
            ResolvedRoute r = route.orElseThrow(() -> ApiException.unavailable("ai.notConfigured"));
            client = r.client();
            model = r.model();
            effort = r.effort();
            providerId = r.providerId();
        } else {
            conversation = conversations.findByIdAndUserId(conversationId, userId)
                    .orElseThrow(() -> ApiException.notFound("ai.conversationNotFound"));
            if (conversation.getTurns() >= MAX_TURNS) {
                throw ApiException.conflict("ai.conversationFull");
            }
            client = providers.client(conversation.getProviderId(), conversation.getProviderKind())
                    .orElseThrow(() -> ApiException.conflict("ai.conversationUnavailable"));
            model = conversation.getModel();
            providerId = conversation.getProviderId();
            // the route's effort while it still names this provider and model
            Conversation c = conversation;
            effort = route.filter(r -> r.providerId() == c.getProviderId() && r.model().equals(c.getModel()))
                    .map(ResolvedRoute::effort).orElse(AiTask.CHAT.defaultEffort());
        }
        Running handle = new Running(conversationId == null ? -1 : conversationId);
        if (running.putIfAbsent(userId, handle) != null) {
            throw ApiException.conflict("ai.busy");
        }
        boolean reserved = false;
        try {
            usage.checkQuota(userId);
            reserved = true;
            boolean created = false;
            if (conversation == null) {
                ResolvedRoute r = route.orElseThrow();
                conversation = conversations.save(new Conversation(userId, title(clean), r.providerId(), r.kind(), r.model(), clock.instant()));
                created = true;
                running.put(userId, handle = new Running(conversation.getId()));
            }
            String providerName = providers.name(providerId).orElse(null);
            return new Turn(userId, conversation.getId(), conversation.getTitle(), created, clean, client, model, effort, providerId,
                    providerName, handle);
        } catch (RuntimeException e) {
            if (reserved) {
                usage.release(userId);
            }
            running.remove(userId);
            throw e;
        }
    }

    /** Runs a prepared turn to its end, reporting to {@code sink}; never throws. */
    public void run(Turn turn, ChatSink sink) {
        long conversationId = turn.conversationId();
        int number = 0;
        int calls = 0;
        LlmUsage total = LlmUsage.ZERO;
        Outcome outcome = Outcome.OK;
        long deadline = System.nanoTime() + TURN_DEADLINE.toNanos();
        try {
            ToolContext ctx = context(turn.userId());
            List<LlmMessage> history = history(conversationId);
            int seq = messages.maxSeq(conversationId);
            number = transactions.execute(status -> conversations.findById(conversationId).orElseThrow().nextTurn(clock.instant()));
            LlmMessage.User question = new LlmMessage.User(List.of(new Part.Context(contextLine(ctx.today())), new Part.Text(turn.text())));
            save(conversationId, ++seq, number, Role.USER, question.parts(), null);
            history.add(question);
            sink.send("start", Map.of("conversationId", conversationId, "title", turn.title(), "turn", number, "created", turn.created()));

            boolean useTools = turn.client().supportsTools(turn.model());
            List<ToolSpec> specs = useTools ? tools.specs() : List.of();
            String system = useTools ? Prompts.CHAT : Prompts.CHAT_SNAPSHOT;
            Listener listener = new Listener(sink, turn.handle(), deadline);
            for (int round = 1; ; round++) {
                List<LlmMessage> request = useTools ? history : withSnapshot(history, tools.snapshot(ctx));
                calls++;
                LlmResponse response = turn.client().send(new LlmRequest(turn.model(), system, request, specs, null, turn.effort(), MAX_TOKENS),
                        listener);
                total = total.plus(response.usage());
                if (response.stopReason() == StopReason.REFUSAL) {
                    outcome = Outcome.REFUSED;
                    fail(conversationId, number, text.get("ai.refused"), sink);
                    return;
                }
                List<Part.ToolCall> toolCalls = useTools ? response.toolCalls() : List.of();
                if (!toolCalls.isEmpty() && response.stopReason() == StopReason.MAX_TOKENS) {
                    // a tool call cut off at the limit may carry truncated arguments: never run it
                    throw new TurnFailure("پاسخ دستیار ناتمام ماند؛ سؤال را کوتاه‌تر یا ساده‌تر بپرسید.");
                }
                save(conversationId, ++seq, number, Role.ASSISTANT, response.parts(), response.nativeMessage());
                history.add(response.toMessage());
                if (toolCalls.isEmpty()) {
                    if (response.stopReason() == StopReason.MAX_TOKENS) {
                        sink.send("notice", Map.of("message", "پاسخ به سقف طول رسید و ممکن است ناقص باشد."));
                    }
                    break;
                }
                if (round >= MAX_ROUNDS) {
                    throw new TurnFailure("دستیار بیش از حد مجاز ابزار به کار گرفت؛ سؤال را ساده‌تر بپرسید.");
                }
                List<Part> results = new ArrayList<>();
                for (Part.ToolCall call : toolCalls) {
                    String label = FinanceTools.label(call.name());
                    sink.send("tool", tool(call, label, "running"));
                    FinanceTools.Outcome result = tools.run(ctx, call);
                    results.add(new Part.ToolResult(call.id(), result.content(), result.error(), result.data()));
                    sink.send("tool", tool(call, label, result.error() ? "error" : "done"));
                    if (result.data() != null) {
                        sink.send("drafts", Map.of("toolId", call.id(), "drafts", drafts(result.data()).stream()
                                .map(d -> DraftView.of(d, false)).toList()));
                    }
                }
                if (round == MAX_ROUNDS - 1) {
                    results.add(new Part.Context(Prompts.LAST_ROUND));
                }
                save(conversationId, ++seq, number, Role.USER, results, null);
                history.add(new LlmMessage.User(results));
            }
            sink.send("done", Map.of("turn", number, "inputTokens", total.inputTokens() + total.cacheReadTokens() + total.cacheWriteTokens(),
                    "outputTokens", total.outputTokens()));
        } catch (LlmException e) {
            boolean stopped = turn.handle().stopped;
            outcome = stopped ? Outcome.STOPPED : Outcome.ERROR;
            String message = stopped ? "پاسخ متوقف شد."
                    : e.kind() == LlmException.Kind.CANCELLED ? "پاسخ‌گویی بیش از حد طول کشید؛ دوباره امتحان کنید." : e.userMessage();
            if (!stopped) {
                log.warn("Chat turn failed: {} {}", e.kind(), e.detail());
            }
            fail(conversationId, number, message, sink);
        } catch (TurnFailure e) {
            outcome = Outcome.ERROR;
            fail(conversationId, number, e.getMessage(), sink);
        } catch (RuntimeException e) {
            log.error("Chat turn failed", e);
            outcome = Outcome.ERROR;
            fail(conversationId, number, text.get("error.internal"), sink);
        } finally {
            if (calls > 0) {
                record(turn, calls, total, outcome);
            } else {
                usage.release(turn.userId());
            }
            running.remove(turn.userId(), turn.handle());
            sink.close();
        }
    }

    /** Asks the user's running turn in this conversation to stop. */
    public void stop(long userId, long conversationId) {
        Running r = running.get(userId);
        if (r != null && r.conversationId == conversationId) {
            r.stop();
        }
    }

    /** Releases a prepared turn that will not run (e.g. it could not be scheduled). */
    public void abandon(Turn turn) {
        usage.release(turn.userId());
        running.remove(turn.userId(), turn.handle());
    }

    private static final class TurnFailure extends RuntimeException {
        TurnFailure(String message) {
            super(message);
        }
    }

    private static final class Listener implements LlmListener {

        private final ChatSink sink;
        private final Running handle;
        private final long deadline;

        Listener(ChatSink sink, Running handle, long deadline) {
            this.sink = sink;
            this.handle = handle;
            this.deadline = deadline;
        }

        @Override
        public void onText(String delta) {
            sink.send("text", Map.of("delta", delta));
        }

        @Override
        public boolean cancelled() {
            return handle.stopped || System.nanoTime() > deadline;
        }

        @Override
        public void streaming(AutoCloseable stream) {
            handle.stream(stream);
        }
    }

    private void fail(long conversationId, int turn, String message, ChatSink sink) {
        if (turn > 0) {
            transactions.executeWithoutResult(status -> {
                messages.exclude(conversationId, turn);
                messages.setError(conversationId, turn, message);
            });
        }
        sink.send("error", Map.of("message", message));
    }

    private void record(Turn turn, int calls, LlmUsage total, Outcome outcome) {
        try {
            usage.record(new AiUsageService.Operation(turn.userId(), AiTask.CHAT.name(), turn.providerId(), turn.providerName(), turn.model(),
                    calls, total, outcome));
        } catch (RuntimeException e) {
            log.warn("Could not record AI usage", e);
        }
    }

    private static Map<String, Object> tool(Part.ToolCall call, String label, String status) {
        return Map.of("id", call.id(), "name", call.name(), "label", label, "status", status);
    }

    private ToolContext context(long userId) {
        UserSettings settings = users.settings(userId);
        return new ToolContext(userId, LocalDate.now(clock.withZone(properties.timezone())), properties.timezone(),
                settings.isAiShareDescriptions(), settings.getInflationRate());
    }

    static String contextLine(LocalDate today) {
        JalaliDate date = JalaliDate.from(today);
        return "امروز: " + date.dayOfWeekName() + " " + date.toDisplayString() + " (" + date + ")";
    }

    /** For models without tools: the data rides along with the latest question, and is not stored. */
    private static List<LlmMessage> withSnapshot(List<LlmMessage> history, String snapshot) {
        List<LlmMessage> copy = new ArrayList<>(history);
        for (int i = copy.size() - 1; i >= 0; i--) {
            if (copy.get(i) instanceof LlmMessage.User user && user.parts().stream().anyMatch(p -> p instanceof Part.Text)) {
                List<Part> parts = new ArrayList<>();
                parts.add(new Part.Context("Snapshot of the user's finances (JSON):\n" + snapshot));
                parts.addAll(user.parts());
                copy.set(i, new LlmMessage.User(parts));
                break;
            }
        }
        return copy;
    }

    private static String title(String message) {
        String line = message.lines().findFirst().orElse(message).strip();
        return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH).strip() + "…";
    }

    // ---------------------------------------------------------------- storage

    private List<LlmMessage> history(long conversationId) {
        List<LlmMessage> history = new ArrayList<>();
        for (ConversationMessage m : messages.findByConversationIdOrderBySeqAsc(conversationId)) {
            if (m.isExcluded()) {
                continue;
            }
            List<Part> parts = parts(m);
            history.add(m.getRole() == Role.USER ? new LlmMessage.User(parts)
                    : new LlmMessage.Assistant(parts, m.getNativeJson() == null ? null : new LlmMessage.Native(m.getNativeFormat(), m.getNativeJson())));
        }
        return history;
    }

    private void save(long conversationId, int seq, int turn, Role role, List<Part> parts, LlmMessage.Native nativeMessage) {
        // written for the declared element type, so each part keeps its "type"
        messages.save(new ConversationMessage(conversationId, seq, turn, role, json.writerFor(PARTS).writeValueAsString(parts),
                nativeMessage == null ? null : nativeMessage.format(), nativeMessage == null ? null : nativeMessage.json()));
    }

    private List<Part> parts(ConversationMessage m) {
        try {
            return json.readValue(m.getParts(), PARTS);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored message " + m.getId() + " is not valid", e);
        }
    }

    private List<Draft> drafts(String data) {
        try {
            return json.readValue(data, DRAFTS);
        } catch (JacksonException e) {
            log.warn("Unreadable drafts in a tool result", e);
            return List.of();
        }
    }

    // ---------------------------------------------------------------- conversations

    @Transactional(readOnly = true)
    public List<Summary> list(long userId) {
        Map<Long, String> names = new LinkedHashMap<>();
        return conversations.findTop100ByUserIdOrderByUpdatedAtDesc(userId).stream().map(c -> new Summary(c.getId(), c.getTitle(), c.getModel(),
                c.getProviderId() == null ? null : names.computeIfAbsent(c.getProviderId(), id -> providers.name(id).orElse(null)),
                providers.client(c.getProviderId(), c.getProviderKind()).isPresent(), c.getTurns(), c.getUpdatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public View get(long userId, long id) {
        Conversation c = require(userId, id);
        List<ConversationMessage> stored = messages.findByConversationIdOrderBySeqAsc(id);
        List<Item> items = new ArrayList<>();
        Map<String, Integer> toolItems = new LinkedHashMap<>();
        List<String> refs = new ArrayList<>();
        Map<Integer, List<Draft>> draftsAt = new LinkedHashMap<>();
        for (ConversationMessage m : stored) {
            List<Part> parts = parts(m);
            if (m.getRole() == Role.USER) {
                for (Part part : parts) {
                    if (part instanceof Part.Text t) {
                        items.add(Item.text("user", m.getTurn(), t.text()));
                    } else if (part instanceof Part.ToolResult r && !m.isExcluded()) {
                        Integer index = toolItems.get(r.callId());
                        if (index != null && r.error()) {
                            Item tool = items.get(index);
                            items.set(index, new Item("tool", tool.turn(), null, tool.toolId(), tool.tool(), tool.label(), "error", null));
                        }
                        if (r.data() != null) {
                            List<Draft> list = drafts(r.data());
                            list.forEach(d -> refs.add(d.ref()));
                            draftsAt.put(items.size(), list);
                            items.add(new Item("drafts", m.getTurn(), null, r.callId(), null, null, null, List.of()));
                        }
                    }
                }
                if (m.getError() != null) {
                    items.add(Item.text("error", m.getTurn(), m.getError()));
                }
            } else if (!m.isExcluded()) {
                for (Part part : parts) {
                    if (part instanceof Part.Text t && !t.text().isBlank()) {
                        Item last = items.isEmpty() ? null : items.getLast();
                        if (last != null && last.kind().equals("assistant") && last.turn() == m.getTurn()) {
                            items.set(items.size() - 1, Item.text("assistant", m.getTurn(), last.text() + "\n\n" + t.text()));
                        } else {
                            items.add(Item.text("assistant", m.getTurn(), t.text()));
                        }
                    } else if (part instanceof Part.ToolCall call) {
                        toolItems.put(call.id(), items.size());
                        items.add(new Item("tool", m.getTurn(), null, call.id(), call.name(), FinanceTools.label(call.name()), "done", null));
                    }
                }
            }
        }
        Set<String> recorded = recorded(userId, refs);
        draftsAt.forEach((index, list) -> {
            Item item = items.get(index);
            items.set(index, new Item("drafts", item.turn(), null, item.toolId(), null, null, null,
                    list.stream().map(d -> DraftView.of(d, recorded.contains(d.ref()))).toList()));
        });
        Running r = running.get(userId);
        boolean available = providers.client(c.getProviderId(), c.getProviderKind()).isPresent();
        return new View(c.getId(), c.getTitle(), c.getModel(), providers.name(c.getProviderId()).orElse(null), available,
                c.getTurns() >= MAX_TURNS, r != null && r.conversationId == id, items);
    }

    private Set<String> recorded(long userId, Collection<String> refs) {
        if (refs.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT external_ref FROM transactions WHERE user_id = :u AND external_ref IN (:refs)")
                .param("u", userId).param("refs", refs).query(String.class).list());
    }

    @Transactional
    public Summary rename(long userId, long id, String title) {
        String clean = title == null ? "" : title.strip();
        if (clean.isEmpty() || clean.length() > 120) {
            throw ApiException.badRequest("ai.invalidTitle");
        }
        Conversation c = require(userId, id);
        c.setTitle(clean);
        return new Summary(c.getId(), c.getTitle(), c.getModel(), providers.name(c.getProviderId()).orElse(null),
                providers.client(c.getProviderId(), c.getProviderKind()).isPresent(), c.getTurns(), c.getUpdatedAt());
    }

    @Transactional
    public void delete(long userId, long id) {
        Running r = running.get(userId);
        if (r != null && r.conversationId == id) {
            throw ApiException.conflict("ai.busy");
        }
        conversations.delete(require(userId, id));
    }

    private Conversation require(long userId, long id) {
        return conversations.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("ai.conversationNotFound"));
    }
}
