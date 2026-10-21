package ir.karname.ai.propose;

import ir.karname.ai.llm.JsonSchema;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

/**
 * One kind of record the assistant can propose (a loan, an account, a budget…): the tool the model
 * calls, how its input becomes the exact request the app's own service takes, and the card the user
 * confirms. Business rules stay in the services: {@link #execute} is run once in a transaction that is
 * always rolled back, to check the request and work out the card's figures, and again for real when
 * the user confirms.
 *
 * @param <P> the request applied on confirmation, stored as JSON in between
 */
public interface ProposalKind<P> {

    /** Stored with the proposal, e.g. {@code LOAN}. */
    String kind();

    String tool();

    /** Persian name of the tool, shown while it runs. */
    String label();

    String description();

    JsonSchema input();

    Class<P> payloadType();

    /**
     * The requests the model's input stands for, normally one; a list kind (budgets) gives one per
     * item, and an item that cannot be used carries the problem for the model instead.
     *
     * @throws IllegalArgumentException with a message the model can act on, for unusable input
     */
    List<Prepared<P>> prepare(Request request);

    /** Records it through the app's own service and returns what the service returned. */
    Object execute(long userId, P payload);

    /** The card, built in the same transaction as {@link #execute} from what it returned. */
    Card card(long userId, P payload, Object result);

    /** Where the user sees what was recorded, e.g. {@code /loans/12}. */
    String link(P payload, Object result);

    /**
     * Work that cannot be done inside a database transaction (asking the stock exchange about a
     * symbol), done before applying; it must be safe to repeat.
     */
    default P beforeApply(long userId, P payload) {
        return payload;
    }

    /** The model's input with what is needed to resolve it against the user's own data. */
    record Request(long userId, LocalDate today, JsonNode input, Resolver.Lookup lookup) {
    }

    /**
     * A request ready to be checked, with what was assumed (shown on the card), or the problem
     * that kept an item of a list from becoming one.
     */
    record Prepared<P>(P payload, List<String> warnings, String problem) {

        public static <P> Prepared<P> of(P payload, List<String> warnings) {
            return new Prepared<>(payload, List.copyOf(warnings), null);
        }

        public static <P> Prepared<P> rejected(String problem) {
            return new Prepared<>(null, List.of(), problem);
        }
    }
}
