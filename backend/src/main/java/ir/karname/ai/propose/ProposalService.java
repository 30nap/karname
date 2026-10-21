package ir.karname.ai.propose;

import ir.karname.ai.guard.Privacy;
import ir.karname.ai.propose.ProposalKind.Prepared;
import ir.karname.common.web.ApiException;
import ir.karname.common.web.Messages;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Proposals the assistant makes and the user decides on. The assistant never records anything: a
 * proposal's request is checked by running the app's own service in a transaction that is always
 * rolled back (which also gives the card its figures), stored, and shown as a card. Only the user's
 * click applies it, through the same service, exactly as it was shown; applying twice records once.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);
    /** The order of the tools, which is part of the cached prompt. */
    private static final List<String> ORDER = List.of("ACCOUNT", "LOAN", "LOAN_PAYMENT", "GOAL", "BUDGET", "CHEQUE", "CHEQUE_STATUS",
            "RECURRING", "CATEGORY", "PRICE", "SECURITY");
    public static final Pattern REF = Pattern.compile("^[0-9a-f]{32}$");
    static final Duration TTL = Duration.ofDays(7);
    static final int MAX_PENDING = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final List<ProposalKind<?>> kinds;
    private final Map<String, ProposalKind<?>> byKind;
    private final Resolver resolver;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Messages messages;
    private final EntityManager entities;
    private final Clock clock;
    private final TransactionTemplate dryRuns;
    private final TransactionTemplate applies;

    public ProposalService(List<ProposalKind<?>> kinds, Resolver resolver, JdbcClient jdbc, JsonMapper json, Messages messages,
            EntityManager entities, Clock clock, PlatformTransactionManager transactionManager) {
        this.kinds = kinds.stream().sorted(Comparator.comparingInt(k -> {
            int i = ORDER.indexOf(k.kind());
            return i < 0 ? ORDER.size() : i;
        })).toList();
        this.byKind = this.kinds.stream().collect(Collectors.toMap(ProposalKind::kind, Function.identity()));
        this.resolver = resolver;
        this.jdbc = jdbc;
        this.json = json;
        this.messages = messages;
        this.entities = entities;
        this.clock = clock;
        this.dryRuns = new TransactionTemplate(transactionManager);
        this.dryRuns.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.applies = new TransactionTemplate(transactionManager);
    }

    /** Every kind, in the fixed order of their tools. */
    public List<ProposalKind<?>> kinds() {
        return kinds;
    }

    /** What the model is told, and the refs of the cards shown to the user. */
    public record Proposed(Map<String, Object> content, List<String> refs) {
    }

    /**
     * Checks the model's input and, for what passes, stores cards for the user to decide on.
     *
     * @throws IllegalArgumentException with problems the model can fix, when nothing could be proposed
     */
    public <P> Proposed propose(long userId, LocalDate today, Long conversationId, int turn, ProposalKind<P> kind, JsonNode input) {
        if (conversationId == null) {
            throw new IllegalArgumentException("Proposals can only be made in a conversation with the user.");
        }
        long pending = jdbc.sql("SELECT count(*) FROM ai_proposals WHERE conversation_id = :c AND status = 'PENDING'")
                .param("c", conversationId).query(Long.class).single();
        if (pending >= MAX_PENDING) {
            throw new IllegalArgumentException("Too many cards are waiting for the user in this conversation; ask them to confirm or dismiss "
                    + "those first.");
        }
        List<Prepared<P>> items = kind.prepare(new ProposalKind.Request(userId, today, input, resolver.lookup(userId, today)));
        List<Map<String, Object>> shown = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        List<Stored> ready = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Prepared<P> item = items.get(i);
            String problem = item.problem();
            Card card = null;
            if (problem == null) {
                try {
                    card = dryRun(userId, kind, item.payload()).withWarnings(item.warnings());
                } catch (ApiException e) {
                    problem = messages.get(e.code(), e.args());
                } catch (IllegalArgumentException e) {
                    problem = e.getMessage();
                } catch (DataIntegrityViolationException e) {
                    log.info("Proposal {} refused by the database", kind.kind(), e);
                    problem = "The database refused this: a value is too long or out of range.";
                }
            }
            if (problem != null) {
                rejected.add(items.size() == 1 ? Map.of("problem", problem) : Map.of("item", i + 1, "problem", problem));
            } else {
                ready.add(new Stored(json.writeValueAsString(item.payload()), card));
                shown.add(card.forModel());
            }
        }
        if (ready.isEmpty()) {
            throw new IllegalArgumentException(rejected.stream().map(r -> r.containsKey("item")
                    ? "item " + r.get("item") + ": " + r.get("problem") : String.valueOf(r.get("problem"))).collect(Collectors.joining(" | ")));
        }
        if (input.path("replaces_previous").asBoolean(false)) {
            jdbc.sql("""
                            UPDATE ai_proposals SET status = 'REPLACED', decided_at = :now
                            WHERE conversation_id = :c AND kind = :k AND status = 'PENDING'
                            """)
                    .param("now", Timestamp.from(clock.instant())).param("c", conversationId).param("k", kind.kind()).update();
        }
        Instant now = clock.instant();
        List<String> refs = new ArrayList<>();
        for (Stored s : ready) {
            String ref = newRef();
            jdbc.sql("""
                            INSERT INTO ai_proposals (ref, user_id, conversation_id, turn, kind, payload, card, created_at, expires_at)
                            VALUES (:ref, :u, :c, :turn, :kind, :payload, :card, :now, :expires)
                            """)
                    .param("ref", ref).param("u", userId).param("c", conversationId).param("turn", turn).param("kind", kind.kind())
                    .param("payload", s.payload()).param("card", json.writeValueAsString(s.card()))
                    .param("now", Timestamp.from(now)).param("expires", Timestamp.from(now.plus(TTL))).update();
            refs.add(ref);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("shown_to_user", refs.size());
        content.put("cards", shown);
        if (!rejected.isEmpty()) {
            content.put("rejected", rejected);
        }
        content.put("note", "Each card is shown to the user with buttons to confirm or dismiss it; nothing is recorded until they "
                + "confirm, so never say it was recorded. Figures on the card were worked out by the app; you may quote them.");
        return new Proposed(content, refs);
    }

    private record Stored(String payload, Card card) {
    }

    /** Runs the request for real in a transaction that is always rolled back, and returns its card. */
    private <P> Card dryRun(long userId, ProposalKind<P> kind, P payload) {
        return dryRuns.execute(status -> {
            // rollback-only from the start: nothing done here is ever committed
            status.setRollbackOnly();
            Object result = kind.execute(userId, payload);
            entities.flush();
            return kind.card(userId, payload, result);
        });
    }

    // ---------------------------------------------------------------- the user's decision

    private record Row(String ref, String kind, String payload, String card, String status, String link, Instant expiresAt) {
    }

    /**
     * Applies a proposal as it was shown. Applying one already applied returns it unchanged, so a
     * double click or a retry records once; a refused request leaves it pending.
     */
    public ProposalView apply(long userId, String ref) {
        Row row = find(userId, ref, false);
        if ("APPLIED".equals(row.status())) {
            return view(row);
        }
        return applyAs(userId, row, decidable(row));
    }

    private <P> ProposalView applyAs(long userId, Row row, ProposalKind<P> kind) {
        String ref = row.ref();
        P payload = read(kind, row.payload());
        // outside the transaction: may ask the stock exchange, and is safe to repeat
        P ready = kind.beforeApply(userId, payload);
        return applies.execute(status -> {
            Row locked = find(userId, ref, true);
            if ("APPLIED".equals(locked.status())) {
                return view(locked);
            }
            decidable(locked);
            Object result = kind.execute(userId, ready);
            String link = kind.link(ready, result);
            jdbc.sql("""
                            UPDATE ai_proposals SET status = 'APPLIED', link = :link, payload = :payload, decided_at = :now, reported = FALSE
                            WHERE ref = :ref AND user_id = :u
                            """)
                    .param("link", link).param("payload", json.writeValueAsString(ready)).param("now", Timestamp.from(clock.instant()))
                    .param("ref", ref).param("u", userId).update();
            return view(find(userId, ref, false));
        });
    }

    public ProposalView dismiss(long userId, String ref) {
        return applies.execute(status -> {
            Row row = find(userId, ref, true);
            switch (row.status()) {
                case "APPLIED" -> throw ApiException.conflict("ai.proposalApplied");
                case "PENDING" -> jdbc.sql("""
                                UPDATE ai_proposals SET status = 'DISMISSED', decided_at = :now, reported = FALSE
                                WHERE ref = :ref AND user_id = :u
                                """)
                        .param("now", Timestamp.from(clock.instant())).param("ref", ref).param("u", userId).update();
                default -> {
                    // dismissed, replaced: nothing to do
                }
            }
            return view(find(userId, ref, false));
        });
    }

    private ProposalKind<?> decidable(Row row) {
        if ("DISMISSED".equals(row.status())) {
            throw ApiException.conflict("ai.proposalDismissed");
        }
        if ("REPLACED".equals(row.status())) {
            throw ApiException.conflict("ai.proposalReplaced");
        }
        if (clock.instant().isAfter(row.expiresAt())) {
            throw ApiException.conflict("ai.proposalExpired");
        }
        ProposalKind<?> kind = byKind.get(row.kind());
        if (kind == null) {
            throw ApiException.conflict("ai.proposalUnavailable");
        }
        return kind;
    }

    private <P> P read(ProposalKind<P> kind, String payload) {
        try {
            return json.readValue(payload, kind.payloadType());
        } catch (JacksonException e) {
            log.warn("Unreadable {} proposal", kind.kind(), e);
            throw ApiException.conflict("ai.proposalUnavailable");
        }
    }

    private Row find(long userId, String ref, boolean lock) {
        if (ref == null || !REF.matcher(ref).matches()) {
            throw ApiException.notFound("ai.proposalNotFound");
        }
        return jdbc.sql("""
                        SELECT ref, kind, payload, card, status, link, expires_at FROM ai_proposals
                        WHERE ref = :ref AND user_id = :u
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("ref", ref).param("u", userId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant()))
                .optional().orElseThrow(() -> ApiException.notFound("ai.proposalNotFound"));
    }

    // ---------------------------------------------------------------- views

    /** The cards for {@code refs} that belong to the user, by ref. */
    public Map<String, ProposalView> views(long userId, Collection<String> refs) {
        List<String> valid = refs.stream().filter(r -> r != null && REF.matcher(r).matches()).distinct().toList();
        if (valid.isEmpty()) {
            return Map.of();
        }
        Map<String, ProposalView> out = new LinkedHashMap<>();
        jdbc.sql("SELECT ref, kind, payload, card, status, link, expires_at FROM ai_proposals WHERE user_id = :u AND ref IN (:refs)")
                .param("u", userId).param("refs", valid)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant()))
                .list().forEach(r -> out.put(r.ref(), view(r)));
        return out;
    }

    private ProposalView view(Row row) {
        Card card = json.readValue(row.card(), Card.class);
        String status = "PENDING".equals(row.status()) && clock.instant().isAfter(row.expiresAt()) ? "EXPIRED" : row.status();
        return new ProposalView(row.ref(), row.kind(), card.title(), card.lines(), card.effects(), card.warnings(), status, row.link(),
                row.expiresAt());
    }

    // ---------------------------------------------------------------- the conversation

    /** A failed turn's cards are no longer part of the conversation: those still waiting are dismissed. */
    public void dismissTurn(long conversationId, int turn) {
        jdbc.sql("""
                        UPDATE ai_proposals SET status = 'DISMISSED', decided_at = :now, reported = TRUE
                        WHERE conversation_id = :c AND turn = :t AND status = 'PENDING'
                        """)
                .param("now", Timestamp.from(clock.instant())).param("c", conversationId).param("t", turn).update();
    }

    /**
     * What the user did with cards since the assistant last heard, as lines for its context (so it
     * neither proposes again what was recorded nor insists on what was dismissed); each is told once.
     */
    public List<String> changesSince(long conversationId) {
        record Change(long id, String card, String status) {
        }
        List<Change> changes = jdbc.sql("""
                        SELECT id, card, status FROM ai_proposals
                        WHERE conversation_id = :c AND reported = FALSE AND status IN ('APPLIED', 'DISMISSED')
                        ORDER BY decided_at, id
                        """)
                .param("c", conversationId).query((rs, n) -> new Change(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
        if (changes.isEmpty()) {
            return List.of();
        }
        jdbc.sql("UPDATE ai_proposals SET reported = TRUE WHERE id IN (:ids)")
                .param("ids", changes.stream().map(Change::id).toList()).update();
        return changes.stream().map(c -> (c.status().equals("APPLIED") ? "The user confirmed and recorded: " : "The user dismissed: ")
                + "«" + Privacy.quote(Privacy.mask(json.readValue(c.card(), Card.class).title())) + "»").toList();
    }

    private static String newRef() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
