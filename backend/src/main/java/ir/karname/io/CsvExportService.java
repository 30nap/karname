package ir.karname.io;

import ir.karname.common.jalali.JalaliDate;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/**
 * Transactions as CSV for spreadsheets: UTF-8 with a byte order mark so Excel shows Persian
 * correctly, Jalali and Gregorian dates, exact amounts in each account's unit (Toman for Rial
 * money), and text cells guarded against formula injection.
 */
@Service
public class CsvExportService {

    private static final String[] HEADER = {"تاریخ", "تاریخ میلادی", "نوع", "حساب", "مبلغ", "واحد", "حساب مقصد", "مبلغ مقصد",
        "واحد مقصد", "کارمزد", "دسته", "شرح", "برچسب‌ها", "یادداشت", "منبع"};
    private static final Map<String, String> TYPES = Map.of("INCOME", "درآمد", "EXPENSE", "هزینه", "TRANSFER", "انتقال",
            "OPENING", "موجودی اول دوره", "ADJUSTMENT", "تطبیق موجودی");
    private static final Map<String, String> SOURCES = Map.of("MANUAL", "دستی", "AI", "دستیار هوشمند", "SMS", "پیامک بانک",
            "RECURRING", "تکراری", "IMPORT", "ورود فایل", "LOAN", "قسط وام", "CHEQUE", "چک", "SYSTEM", "سیستم");

    private final JdbcClient jdbc;

    public CsvExportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public void writeTransactions(long userId, LocalDate from, LocalDate to, OutputStream out) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.type, t.occurred_on, a.name AS account, ca.code AS commodity, t.amount, ta.name AS to_account,
                       tc.code AS to_commodity, t.to_amount, t.fee, c.name AS category, pc.name AS parent_category,
                       t.description, t.tags, t.notes, t.source
                FROM transactions t
                JOIN accounts a ON a.id = t.account_id
                JOIN commodities ca ON ca.id = a.commodity_id
                LEFT JOIN accounts ta ON ta.id = t.to_account_id
                LEFT JOIN commodities tc ON tc.id = ta.commodity_id
                LEFT JOIN categories c ON c.id = t.category_id
                LEFT JOIN categories pc ON pc.id = c.parent_id
                WHERE t.user_id = :u
                """);
        if (from != null) {
            sql.append(" AND t.occurred_on >= :from");
        }
        if (to != null) {
            sql.append(" AND t.occurred_on <= :to");
        }
        sql.append(" ORDER BY t.occurred_on, t.id");
        try {
            out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            CSVPrinter csv = new CSVPrinter(writer, CSVFormat.EXCEL.builder().setHeader(HEADER).get());
            var statement = jdbc.sql(sql.toString()).param("u", userId);
            if (from != null) {
                statement = statement.param("from", from);
            }
            if (to != null) {
                statement = statement.param("to", to);
            }
            statement.query(rs -> {
                LocalDate date = rs.getObject("occurred_on", LocalDate.class);
                String category = rs.getString("category");
                String parent = rs.getString("parent_category");
                try {
                    csv.printRecord(
                            JalaliDate.from(date).toString(),
                            date.toString(),
                            TYPES.getOrDefault(rs.getString("type"), rs.getString("type")),
                            text(rs.getString("account")),
                            plain(rs.getBigDecimal("amount")),
                            rs.getString("commodity"),
                            text(rs.getString("to_account")),
                            plain(rs.getBigDecimal("to_amount")),
                            rs.getString("to_commodity"),
                            plain(rs.getBigDecimal("fee")),
                            text(category == null ? null : parent == null ? category : parent + " / " + category),
                            text(rs.getString("description")),
                            text(rs.getString("tags")),
                            text(rs.getString("notes")),
                            SOURCES.getOrDefault(rs.getString("source"), rs.getString("source")));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            csv.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** Spreadsheets run cells starting with = + - @ as formulas; a leading apostrophe keeps them text. */
    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' ? "'" + value : value;
    }
}
