package ir.karname.io;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.category.MerchantRuleService;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.persian.PersianNumbers;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import ir.karname.transaction.TransactionRepository;
import ir.karname.transaction.TransactionService;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionSource;
import ir.karname.transaction.TransactionType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports a bank statement exported as CSV: columns are recognised from Persian or English headers
 * (or chosen by the user), dates may be Jalali or Gregorian, amounts in Rial or Toman, with
 * separate withdrawal/deposit columns or one signed amount. Rows are previewed with suggested
 * categories and likely duplicates before anything is recorded; recording is idempotent.
 */
@Service
public class StatementImportService {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_ROWS = 2000;
    private static final Pattern REF = Pattern.compile("^import:[0-9a-f]{32}$");
    private static final Pattern DATE = Pattern.compile("(\\d{4})\\s*[/\\-.]\\s*(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})|(\\d{4})(\\d{2})(\\d{2})");
    private static final Charset WINDOWS_1256 = Charset.forName("windows-1256");

    private final AccountService accounts;
    private final MerchantRuleService merchantRules;
    private final TransactionService transactionService;
    private final TransactionRepository transactions;
    private final JdbcClient jdbc;

    public StatementImportService(AccountService accounts, MerchantRuleService merchantRules, TransactionService transactionService,
            TransactionRepository transactions, JdbcClient jdbc) {
        this.accounts = accounts;
        this.merchantRules = merchantRules;
        this.transactionService = transactionService;
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    public enum DateStyle { AUTO, JALALI, GREGORIAN }

    public enum AmountUnit { RIAL, TOMAN }

    /** Which column holds what (0-based); null fields are detected from the header row. */
    public record Mapping(Integer date, Integer description, Integer amount, Integer debit, Integer credit, DateStyle dateStyle,
            AmountUnit unit, Boolean hasHeader) {
    }

    public record Row(int line, LocalDate date, BigDecimal amount, String description, Long categoryId, boolean duplicate, String ref,
            String error) {
    }

    public record Preview(List<String> headers, List<List<String>> sample, Mapping mapping, List<Row> rows) {
    }

    public record CommitRow(LocalDate date, BigDecimal amount, String description, Long categoryId, String ref) {
    }

    public record CommitRequest(Long accountId, List<CommitRow> rows) {
    }

    public record CommitResult(int created, int skipped) {
    }

    @Transactional(readOnly = true)
    public Preview preview(long userId, long accountId, byte[] file, Mapping requested) {
        Account account = importable(userId, accountId);
        List<List<String>> records = parse(file);
        if (records.isEmpty()) {
            throw ApiException.badRequest("import.empty");
        }
        Mapping mapping = complete(records, requested);
        List<String> headers = mapping.hasHeader() ? records.getFirst() : List.of();
        List<List<String>> data = mapping.hasHeader() ? records.subList(1, records.size()) : records;
        if (data.size() > MAX_ROWS) {
            throw ApiException.badRequest("import.tooManyRows", MAX_ROWS);
        }
        Map<String, Integer> seen = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        int firstLine = mapping.hasHeader() ? 2 : 1;
        for (int i = 0; i < data.size(); i++) {
            List<String> record = data.get(i);
            if (record.stream().allMatch(v -> v == null || v.isBlank())) {
                continue;
            }
            rows.add(row(userId, account, record, firstLine + i, mapping, seen));
        }
        return new Preview(headers, records.subList(0, Math.min(records.size(), 6)), mapping, rows);
    }

    @Transactional
    public CommitResult commit(long userId, CommitRequest request) {
        if (request == null || request.accountId() == null || request.rows() == null) {
            throw ApiException.badRequest("import.invalidRows");
        }
        if (request.rows().size() > MAX_ROWS) {
            throw ApiException.badRequest("import.tooManyRows", MAX_ROWS);
        }
        Account account = importable(userId, request.accountId());
        int created = 0;
        int skipped = 0;
        for (CommitRow r : request.rows()) {
            if (r == null || r.date() == null || r.amount() == null || r.amount().signum() == 0 || r.ref() == null || !REF.matcher(r.ref()).matches()) {
                throw ApiException.badRequest("import.invalidRows");
            }
            if (transactions.existsByUserIdAndExternalRef(userId, r.ref())) {
                skipped++;
                continue;
            }
            TransactionType type = r.amount().signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME;
            String description = PersianText.clean(r.description());
            if (description != null && description.length() > 300) {
                description = description.substring(0, 300);
            }
            transactionService.create(userId, new TransactionRequest(type, r.date(), account.getId(), r.amount().abs(), null, null, null,
                    r.categoryId(), description, null, List.of()), TransactionSource.IMPORT, r.ref());
            created++;
        }
        return new CommitResult(created, skipped);
    }

    private Row row(long userId, Account account, List<String> record, int line, Mapping m, Map<String, Integer> seen) {
        String description = PersianText.clean(cell(record, m.description()));
        LocalDate date;
        try {
            date = date(cell(record, m.date()), m.dateStyle());
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return new Row(line, null, null, description, null, false, null, "تاریخ «" + nonNull(cell(record, m.date())) + "» خوانده نشد.");
        }
        Optional<BigDecimal> amount = amount(record, m);
        if (amount.isEmpty()) {
            return new Row(line, date, null, description, null, false, null, "مبلغ خوانده نشد.");
        }
        BigDecimal toman = m.unit() == AmountUnit.RIAL ? amount.get().movePointLeft(1) : amount.get();
        toman = toman.stripTrailingZeros();
        if (toman.scale() < 0) {
            toman = toman.setScale(0);
        }
        // identical rows in one file (two same-price coffees on a day) stay distinct
        String key = account.getId() + "|" + date + "|" + toman.toPlainString() + "|" + nonNull(description);
        int occurrence = seen.merge(key, 1, Integer::sum);
        String ref = "import:" + hash(key + "|" + occurrence);
        TransactionType type = toman.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME;
        boolean duplicate = transactions.existsByUserIdAndExternalRef(userId, ref) || jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM transactions WHERE user_id = :u AND account_id = :a AND occurred_on = :d
                                       AND type = :t AND amount = :amount)
                        """)
                .param("u", userId).param("a", account.getId()).param("d", date).param("t", type.name()).param("amount", toman.abs())
                .query(Boolean.class).single();
        Long category = description == null ? null : merchantRules.suggest(userId, description).orElse(null);
        return new Row(line, date, toman, description, category, duplicate, ref, null);
    }

    private static Optional<BigDecimal> amount(List<String> record, Mapping m) {
        if (m.amount() != null) {
            return signed(cell(record, m.amount()));
        }
        Optional<BigDecimal> debit = signed(cell(record, m.debit())).map(BigDecimal::abs).filter(v -> v.signum() != 0);
        Optional<BigDecimal> credit = signed(cell(record, m.credit())).map(BigDecimal::abs).filter(v -> v.signum() != 0);
        if (debit.isPresent() == credit.isPresent()) {
            return Optional.empty();
        }
        return debit.isPresent() ? debit.map(BigDecimal::negate) : credit;
    }

    /** "1,250,000", "(1,250,000)" and "1,250,000-" (as some banks print debits). */
    static Optional<BigDecimal> signed(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String s = PersianText.normalizeDigits(text.trim()).replace("ریال", "").replace("تومان", "").trim();
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        } else if (s.endsWith("-") || s.endsWith("−")) {
            negative = true;
            s = s.substring(0, s.length() - 1);
        }
        boolean minus = negative;
        return PersianNumbers.parseDecimal(s).map(v -> minus ? v.negate() : v);
    }

    static LocalDate date(String text, DateStyle style) {
        if (text == null) {
            throw new IllegalArgumentException("no date");
        }
        Matcher m = DATE.matcher(PersianText.normalizeDigits(text.trim()));
        if (!m.find()) {
            throw new IllegalArgumentException("no date");
        }
        int y = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(4));
        int mo = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(5));
        int d = Integer.parseInt(m.group(3) != null ? m.group(3) : m.group(6));
        boolean gregorian = style == DateStyle.GREGORIAN || (style == DateStyle.AUTO && y >= 1700);
        return gregorian ? LocalDate.of(y, mo, d) : JalaliDate.of(y, mo, d).toGregorian();
    }

    /** Fills in what the user left open, from header names (Persian or English). */
    private static Mapping complete(List<List<String>> records, Mapping r) {
        Mapping m = r == null ? new Mapping(null, null, null, null, null, null, null, null) : r;
        List<String> first = records.getFirst();
        boolean header = m.hasHeader() != null ? m.hasHeader() : looksLikeHeader(first);
        Integer date = m.date();
        Integer description = m.description();
        Integer amount = m.amount();
        Integer debit = m.debit();
        Integer credit = m.credit();
        AmountUnit unit = m.unit();
        if (header) {
            for (int i = 0; i < first.size(); i++) {
                String h = normalize(first.get(i));
                if (date == null && (h.contains("تاریخ") || h.contains("date"))) {
                    date = i;
                } else if (description == null && (h.contains("شرح") || h.contains("توضیح") || h.contains("بابت") || h.contains("description")
                        || h.contains("narrative"))) {
                    description = i;
                } else if (debit == null && (h.contains("برداشت") || h.contains("بدهکار") || h.contains("debit") || h.contains("withdraw"))) {
                    debit = i;
                } else if (credit == null && (h.contains("واریز") || h.contains("بستانکار") || h.contains("credit") || h.contains("deposit"))) {
                    credit = i;
                } else if (amount == null && (h.equals("مبلغ") || h.startsWith("مبلغ ") || h.contains("amount"))) {
                    amount = i;
                }
                if (unit == null && (h.contains("ریال") || h.contains("rial"))) {
                    unit = AmountUnit.RIAL;
                } else if (unit == null && (h.contains("تومان") || h.contains("toman"))) {
                    unit = AmountUnit.TOMAN;
                }
            }
        }
        if (debit != null || credit != null) {
            amount = m.amount();
        }
        if (date == null || (amount == null && debit == null && credit == null)) {
            throw ApiException.badRequest("import.mappingRequired");
        }
        int width = first.size();
        for (Integer column : new Integer[] {date, description, amount, debit, credit}) {
            if (column != null && (column < 0 || column >= width)) {
                throw ApiException.badRequest("import.mappingRequired");
            }
        }
        // Iranian banks export Rial unless a header says otherwise
        return new Mapping(date, description, amount, debit, credit, m.dateStyle() == null ? DateStyle.AUTO : m.dateStyle(),
                unit == null ? AmountUnit.RIAL : unit, header);
    }

    private static boolean looksLikeHeader(List<String> first) {
        for (String value : first) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String v = PersianText.normalizeDigits(value);
            if (DATE.matcher(v).find() || PersianNumbers.parseDecimal(v).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /** CSV in UTF-8 (with or without a byte order mark) or Windows-1256, as older bank exports use. */
    static List<List<String>> parse(byte[] file) {
        if (file == null || file.length == 0) {
            throw ApiException.badRequest("import.empty");
        }
        if (file.length > MAX_BYTES) {
            throw ApiException.badRequest("import.tooLarge");
        }
        String text = decode(file);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String firstLine = text.lines().findFirst().orElse("");
        char delimiter = delimiter(firstLine);
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreSurroundingSpaces(true).get()
                .parse(new StringReader(text))) {
            List<List<String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (rows.size() > MAX_ROWS + 1) {
                    throw ApiException.badRequest("import.tooManyRows", MAX_ROWS);
                }
                rows.add(record.toList());
            }
            return rows;
        } catch (IOException | java.io.UncheckedIOException | IllegalStateException e) {
            throw ApiException.badRequest("import.invalidCsv");
        }
    }

    private static String decode(byte[] file) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file)).toString();
        } catch (CharacterCodingException e) {
            return new String(file, WINDOWS_1256);
        }
    }

    private static char delimiter(String line) {
        char best = ',';
        long count = line.chars().filter(c -> c == ',').count();
        for (char c : new char[] {';', '\t'}) {
            long n = line.chars().filter(x -> x == c).count();
            if (n > count) {
                best = c;
                count = n;
            }
        }
        return best;
    }

    private Account importable(long userId, long accountId) {
        Account account = accounts.require(userId, accountId);
        if (account.isArchived()) {
            throw ApiException.badRequest("import.accountArchived");
        }
        return account;
    }

    private static String cell(List<String> record, Integer column) {
        return column == null || column >= record.size() ? null : record.get(column);
    }

    private static String normalize(String header) {
        String h = PersianText.clean(header);
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
