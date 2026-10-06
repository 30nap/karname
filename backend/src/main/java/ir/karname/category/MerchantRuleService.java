package ir.karname.category;

import ir.karname.common.persian.PersianText;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Learns which category a description belongs to ("اسنپ" → taxi) from the user's own choices, so
 * repeated merchants are categorized deterministically and for free.
 */
@Service
public class MerchantRuleService {

    private static final int MAX_KEY_LENGTH = 120;

    private final JdbcClient jdbc;

    public MerchantRuleService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Normalized key of a description: no digits or punctuation, collapsed spaces. */
    public static String key(String description) {
        if (description == null) {
            return "";
        }
        String k = PersianText.normalizeForSearch(description)
                .replaceAll("[0-9]", " ")
                .replaceAll("[\\p{Punct}،؛؟«»٪]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return k.length() > MAX_KEY_LENGTH ? k.substring(0, MAX_KEY_LENGTH).trim() : k;
    }

    @Transactional
    public void learn(long userId, String description, long categoryId) {
        String key = key(description);
        if (key.length() < 2) {
            return;
        }
        jdbc.sql("""
                INSERT INTO merchant_rules (user_id, pattern, category_id, hits, updated_at) VALUES (:userId, :pattern, :categoryId, 1, now())
                ON CONFLICT (user_id, pattern) DO UPDATE SET
                    category_id = EXCLUDED.category_id,
                    hits = CASE WHEN merchant_rules.category_id = EXCLUDED.category_id THEN merchant_rules.hits + 1 ELSE 1 END,
                    updated_at = now()
                """)
                .param("userId", userId)
                .param("pattern", key)
                .param("categoryId", categoryId)
                .update();
    }

    /**
     * Suggests a category for a description: an exact match of the normalized description, or the
     * longest learned pattern contained in it.
     */
    @Transactional(readOnly = true)
    public Optional<Long> suggest(long userId, String description) {
        String key = key(description);
        if (key.length() < 2) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT m.category_id FROM merchant_rules m
                JOIN categories c ON c.id = m.category_id AND NOT c.archived
                WHERE m.user_id = :userId AND (m.pattern = :key OR (length(m.pattern) >= 3 AND position(m.pattern IN :key) > 0))
                ORDER BY (m.pattern = :key) DESC, length(m.pattern) DESC, m.hits DESC
                LIMIT 1
                """)
                .param("userId", userId)
                .param("key", key)
                .query(Long.class)
                .optional();
    }
}
