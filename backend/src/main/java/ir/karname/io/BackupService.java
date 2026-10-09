package ir.karname.io;

import ir.karname.common.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full backup of one user's data as JSON, and restore that replaces it. The format mirrors the
 * database tables (snake_case columns) so it stays complete as the schema grows; ids are only
 * references inside the file and are reassigned on restore, so a backup can also move data to
 * another account or instance. Commodities are referenced by code.
 */
@Service
public class BackupService {

    public static final String FORMAT = "karname-backup";
    public static final int VERSION = 1;
    private static final String COMMODITY = "#commodity";
    /** A reference to instance-wide settings (an AI provider): not portable, so restored as empty. */
    private static final String DETACHED = "#detached";
    private static final int MAX_ROWS = 500_000;
    private static final Pattern CUSTOM_CODE = Pattern.compile("^C_[0-9A-F]{8}$");

    /** A user-owned table, in restore order: referenced tables come first. */
    private record Table(String name, String owner, Map<String, String> refs, boolean hasId) {
    }

    private static final List<Table> TABLES = List.of(
            new Table("commodities", "user_id = :u", Map.of(), true),
            new Table("categories", "user_id = :u", Map.of("parent_id", "categories"), true),
            new Table("merchant_rules", "user_id = :u", Map.of("category_id", "categories"), true),
            new Table("accounts", "user_id = :u", Map.of("commodity_id", COMMODITY), true),
            new Table("loans", "user_id = :u", Map.of("account_id", "accounts", "payment_account_id", "accounts"), true),
            new Table("loan_installments", "loan_id IN (SELECT id FROM loans WHERE user_id = :u)", Map.of("loan_id", "loans"), true),
            new Table("recurring_rules", "user_id = :u",
                    Map.of("account_id", "accounts", "to_account_id", "accounts", "category_id", "categories"), true),
            new Table("recurring_skips", "rule_id IN (SELECT id FROM recurring_rules WHERE user_id = :u)",
                    Map.of("rule_id", "recurring_rules"), false),
            new Table("cheques", "user_id = :u",
                    Map.of("account_id", "accounts", "counter_account_id", "accounts", "category_id", "categories"), true),
            new Table("budgets", "user_id = :u", Map.of("category_id", "categories"), true),
            new Table("goals", "user_id = :u", Map.of("commodity_id", COMMODITY), true),
            new Table("goal_accounts", "goal_id IN (SELECT id FROM goals WHERE user_id = :u)", Map.of("goal_id", "goals", "account_id", "accounts"),
                    false),
            new Table("transactions", "user_id = :u",
                    Map.of("account_id", "accounts", "to_account_id", "accounts", "category_id", "categories"), true),
            // the user's own prices: entered by hand or implied by their exchanges
            new Table("prices", "user_id = :u", Map.of("commodity_id", COMMODITY, "transaction_id", "transactions"), true),
            // conversations come back as history: they cannot continue on another instance's provider
            new Table("ai_conversations", "user_id = :u", Map.of("provider_id", DETACHED), true),
            new Table("ai_messages", "conversation_id IN (SELECT id FROM ai_conversations WHERE user_id = :u)",
                    Map.of("conversation_id", "ai_conversations"), true),
            new Table("ai_reports", "user_id = :u", Map.of(), true));

    /** Columns never copied: the owner is the restoring user, ids are reassigned, versions restart. */
    private static final Set<String> SKIPPED = Set.of("id", "user_id", "version");
    private static final List<String> SETTINGS = List.of("display_unit", "digit_style", "theme", "wealth_units", "inflation_rate",
            "ai_enabled", "ai_share_descriptions");
    /** Transactions created by a loan, rule or cheque carry its id in their external reference. */
    private static final Pattern REF = Pattern.compile("^(loan|rec|cheque):(\\d+)(.*)$");
    private static final Map<String, String> REF_TABLES = Map.of("loan", "loans", "rec", "recurring_rules", "cheque", "cheques");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Map<String, Map<String, String>> columnTypes = new ConcurrentHashMap<>();

    public BackupService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Backup(String format, int version, Instant exportedAt, Map<String, Object> settings,
            Map<String, List<Map<String, Object>>> tables) {
    }

    public record RestoreSummary(Map<String, Integer> rows) {
    }

    @Transactional(readOnly = true)
    public Backup export(long userId) {
        Map<Long, String> codes = new HashMap<>();
        jdbc.sql("SELECT id, code FROM commodities WHERE user_id IS NULL OR user_id = ?").param(userId)
                .query(rs -> {
                    codes.put(rs.getLong(1), rs.getString(2));
                });
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        for (Table table : TABLES) {
            String order = table.hasId() ? "id" : "1, 2";
            List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM " + table.name() + " WHERE " + table.owner() + " ORDER BY " + order)
                    .param("u", userId)
                    .query((rs, n) -> row(rs, table, codes))
                    .list();
            tables.put(table.name(), rows);
        }
        Map<String, Object> settings = jdbc.sql("SELECT * FROM user_settings WHERE user_id = ?").param(userId)
                .query((rs, n) -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (String column : SETTINGS) {
                        values.put(column, value(rs, rs.findColumn(column)));
                    }
                    return values;
                })
                .optional().orElse(Map.of());
        return new Backup(FORMAT, VERSION, clock.instant(), settings, tables);
    }

    /** Replaces all of the user's data with the backup's, atomically: a bad file changes nothing. */
    @Transactional
    public RestoreSummary restore(long userId, Backup backup) {
        if (backup == null || !FORMAT.equals(backup.format()) || backup.tables() == null) {
            throw ApiException.badRequest("backup.invalidFormat");
        }
        if (backup.version() < 1 || backup.version() > VERSION) {
            throw ApiException.badRequest("backup.unsupportedVersion", backup.version());
        }
        long total = backup.tables().values().stream().mapToLong(rows -> rows == null ? 0 : rows.size()).sum();
        if (total > MAX_ROWS) {
            throw ApiException.badRequest("backup.tooLarge");
        }
        for (String name : backup.tables().keySet()) {
            if (TABLES.stream().noneMatch(t -> t.name().equals(name))) {
                throw ApiException.badRequest("backup.unknownTable", name);
            }
        }
        checkCustomCodes(backup.tables().get("commodities"));
        deleteUserData(userId);

        Map<String, Map<Long, Long>> ids = new HashMap<>();
        Map<String, Long> commodityIds = new HashMap<>();
        jdbc.sql("SELECT code, id FROM commodities WHERE user_id IS NULL").query(rs -> {
            commodityIds.put(rs.getString(1), rs.getLong(2));
        });
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Table table : TABLES) {
            List<Map<String, Object>> rows = backup.tables().getOrDefault(table.name(), List.of());
            if (rows == null) {
                rows = List.of();
            }
            Map<Long, Long> mapping = new HashMap<>();
            ids.put(table.name(), mapping);
            for (Map<String, Object> row : ordered(table, rows)) {
                Long newId = insert(userId, table, row, ids, commodityIds);
                if (table.hasId()) {
                    mapping.put(oldId(row, table), newId);
                }
                if (table.name().equals("commodities")) {
                    commodityIds.put(String.valueOf(row.get("code")), newId);
                }
            }
            counts.put(table.name(), rows.size());
        }
        restoreSettings(userId, backup.settings());
        return new RestoreSummary(counts);
    }

    /**
     * Custom units keep their code, which must look like one ("C_…") and be unique: a custom unit
     * coded "USD" would shadow the built-in one and break every lookup of it for this user.
     */
    private static void checkCustomCodes(List<Map<String, Object>> rows) {
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            String code = row == null ? null : String.valueOf(row.get("code"));
            if (code == null || !CUSTOM_CODE.matcher(code).matches() || !seen.add(code)) {
                throw ApiException.badRequest("backup.invalidCommodity", String.valueOf(code));
            }
        }
    }

    private void deleteUserData(long userId) {
        // children first; most of the rest would cascade, but explicit order keeps it predictable
        for (String sql : List.of(
                "DELETE FROM ai_reports WHERE user_id = ?",
                "DELETE FROM ai_conversations WHERE user_id = ?",
                "DELETE FROM notifications WHERE user_id = ?",
                "DELETE FROM prices WHERE user_id = ?",
                "DELETE FROM transactions WHERE user_id = ?",
                "DELETE FROM goals WHERE user_id = ?",
                "DELETE FROM budgets WHERE user_id = ?",
                "DELETE FROM cheques WHERE user_id = ?",
                "DELETE FROM recurring_rules WHERE user_id = ?",
                "DELETE FROM loans WHERE user_id = ?",
                "DELETE FROM accounts WHERE user_id = ?",
                "DELETE FROM merchant_rules WHERE user_id = ?",
                "DELETE FROM categories WHERE user_id = ?",
                "DELETE FROM commodities WHERE user_id = ?")) {
            jdbc.sql(sql).param(userId).update();
        }
    }

    /** Parents before children within a self-referencing table (categories). */
    private static List<Map<String, Object>> ordered(Table table, List<Map<String, Object>> rows) {
        String selfRef = table.refs().entrySet().stream().filter(e -> e.getValue().equals(table.name())).map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (selfRef == null) {
            return rows;
        }
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        Set<Long> placed = new java.util.HashSet<>();
        List<Map<String, Object>> pending = new ArrayList<>(rows);
        while (!pending.isEmpty()) {
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> row : pending) {
                Object parent = row.get(selfRef);
                if (parent == null || placed.contains(asLong(parent, selfRef))) {
                    result.add(row);
                    placed.add(oldId(row, table));
                } else {
                    next.add(row);
                }
            }
            if (next.size() == pending.size()) {
                throw ApiException.badRequest("backup.invalidReference", table.name());
            }
            pending = next;
        }
        return result;
    }

    private Long insert(long userId, Table table, Map<String, Object> row, Map<String, Map<Long, Long>> ids, Map<String, Long> commodityIds) {
        Map<String, String> types = columnTypes(table.name());
        StringJoiner columns = new StringJoiner(", ");
        StringJoiner values = new StringJoiner(", ");
        Map<String, Object> params = new LinkedHashMap<>();
        if (types.containsKey("user_id")) {
            columns.add("user_id");
            values.add(":user_id");
            params.put("user_id", userId);
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String column = e.getKey();
            if (SKIPPED.contains(column)) {
                continue;
            }
            String type = types.get(column);
            if (type == null) {
                throw ApiException.badRequest("backup.unknownColumn", table.name() + "." + column);
            }
            Object value = e.getValue();
            String ref = table.refs().get(column);
            if (DETACHED.equals(ref)) {
                value = null;
            } else if (value != null && ref != null) {
                value = ref.equals(COMMODITY) ? commodity(commodityIds, value, table) : mapped(ids.get(ref), value, table, column);
            } else if (value != null && table.name().equals("transactions") && column.equals("external_ref")) {
                value = rewriteRef(String.valueOf(value), ids);
            } else {
                value = convert(value, type, table.name(), column);
            }
            columns.add(column);
            values.add(":" + column);
            params.put(column, value);
        }
        String sql = "INSERT INTO " + table.name() + " (" + columns + ") VALUES (" + values + ")";
        try {
            if (table.hasId()) {
                return jdbc.sql(sql + " RETURNING id").params(params).query(Long.class).single();
            }
            jdbc.sql(sql).params(params).update();
            return null;
        } catch (org.springframework.dao.DataAccessException e) {
            throw ApiException.badRequest("backup.invalidRow", table.name());
        }
    }

    private void restoreSettings(long userId, Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return;
        }
        Map<String, String> types = columnTypes("user_settings");
        StringJoiner sets = new StringJoiner(", ");
        Map<String, Object> params = new HashMap<>();
        for (String column : SETTINGS) {
            if (settings.containsKey(column) && types.containsKey(column)) {
                sets.add(column + " = :" + column);
                params.put(column, convert(settings.get(column), types.get(column), "user_settings", column));
            }
        }
        if (params.isEmpty()) {
            return;
        }
        params.put("u", userId);
        try {
            jdbc.sql("UPDATE user_settings SET " + sets + ", updated_at = now(), version = version + 1 WHERE user_id = :u").params(params).update();
        } catch (org.springframework.dao.DataAccessException e) {
            throw ApiException.badRequest("backup.invalidRow", "user_settings");
        }
    }

    private static Long commodity(Map<String, Long> commodityIds, Object code, Table table) {
        Long id = commodityIds.get(String.valueOf(code));
        if (id == null) {
            throw ApiException.badRequest("backup.unknownCommodity", String.valueOf(code));
        }
        return id;
    }

    private static Long mapped(Map<Long, Long> mapping, Object oldId, Table table, String column) {
        Long id = mapping == null ? null : mapping.get(asLong(oldId, column));
        if (id == null) {
            throw ApiException.badRequest("backup.invalidReference", table.name() + "." + column);
        }
        return id;
    }

    private static String rewriteRef(String ref, Map<String, Map<Long, Long>> ids) {
        Matcher m = REF.matcher(ref);
        if (!m.matches()) {
            return ref;
        }
        Long id = ids.get(REF_TABLES.get(m.group(1))).get(Long.parseLong(m.group(2)));
        if (id == null) {
            throw ApiException.badRequest("backup.invalidReference", "transactions.external_ref");
        }
        return m.group(1) + ":" + id + m.group(3);
    }

    private static Long oldId(Map<String, Object> row, Table table) {
        Object id = row.get("id");
        if (id == null) {
            throw ApiException.badRequest("backup.invalidRow", table.name());
        }
        return asLong(id, "id");
    }

    private static long asLong(Object value, String column) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("backup.invalidValue", column);
        }
    }

    /** JSON value to the column's SQL type; only the types the schema uses are accepted. */
    private static Object convert(Object value, String type, String table, String column) {
        if (value == null) {
            return null;
        }
        try {
            return switch (type) {
                case "numeric" -> new BigDecimal(String.valueOf(value));
                case "date" -> LocalDate.parse(String.valueOf(value));
                case "timestamp with time zone" -> Timestamp.from(Instant.parse(String.valueOf(value)));
                case "boolean" -> value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
                case "smallint", "integer" -> (int) asLong(value, column);
                case "bigint" -> asLong(value, column);
                case "character varying", "text" -> value instanceof String s ? s : String.valueOf(value);
                default -> throw ApiException.badRequest("backup.invalidValue", table + "." + column);
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw ApiException.badRequest("backup.invalidValue", table + "." + column);
        }
    }

    private Map<String, String> columnTypes(String table) {
        return columnTypes.computeIfAbsent(table, t -> {
            Map<String, String> types = new HashMap<>();
            jdbc.sql("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ?")
                    .param(t)
                    .query(rs -> {
                        types.put(rs.getString(1), rs.getString(2));
                    });
            return Map.copyOf(types);
        });
    }

    private static Map<String, Object> row(ResultSet rs, Table table, Map<Long, String> codes) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String column = md.getColumnName(i);
            if (column.equals("user_id") || column.equals("version")) {
                continue;
            }
            Object value = value(rs, i);
            if (value != null && COMMODITY.equals(table.refs().get(column))) {
                value = codes.get(((Number) value).longValue());
            } else if (DETACHED.equals(table.refs().get(column))) {
                value = null;
            }
            row.put(column, value);
        }
        return row;
    }

    /** Exact, portable JSON values: decimals and instants as text. */
    private static Object value(ResultSet rs, int i) throws SQLException {
        int type = rs.getMetaData().getColumnType(i);
        Object value = switch (type) {
            case Types.NUMERIC, Types.DECIMAL -> {
                BigDecimal d = rs.getBigDecimal(i);
                yield d == null ? null : d.stripTrailingZeros().toPlainString();
            }
            case Types.DATE -> {
                LocalDate d = rs.getObject(i, LocalDate.class);
                yield d == null ? null : d.toString();
            }
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                Timestamp t = rs.getTimestamp(i);
                yield t == null ? null : t.toInstant().toString();
            }
            case Types.BOOLEAN, Types.BIT -> rs.getBoolean(i);
            case Types.SMALLINT, Types.INTEGER, Types.BIGINT -> rs.getLong(i);
            default -> rs.getString(i);
        };
        return rs.wasNull() ? null : value;
    }
}
