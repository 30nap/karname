package ir.karname.ai.propose;

import java.time.Instant;
import java.util.List;

/**
 * A proposal card as the app shows it. {@code status}: PENDING (waiting for the user), APPLIED (with
 * {@code link} to what was recorded), DISMISSED, REPLACED (the assistant proposed a corrected one) or
 * EXPIRED (not decided in time).
 */
public record ProposalView(String ref, String kind, String title, List<Card.Line> lines, List<String> effects, List<String> warnings,
        String status, String link, Instant expiresAt) {
}
