package ir.karname.ai.eval;

import ir.karname.account.Account;
import ir.karname.account.AccountService.AccountRequest;
import ir.karname.account.AccountType;
import ir.karname.account.Bank;
import ir.karname.ai.capture.CaptureService;
import ir.karname.ai.capture.CaptureService.SmsResult;
import ir.karname.ai.capture.DraftView;
import ir.karname.ai.provider.AiPreset;
import ir.karname.ai.provider.AiProviderService.ProviderRequest;
import ir.karname.ai.provider.AiProviderService.ProviderView;
import ir.karname.ai.provider.AiProviderService.RouteRequest;
import ir.karname.ai.provider.AiTask;
import ir.karname.support.AiTestSupport;
import ir.karname.support.TestUser;
import ir.karname.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persian notes and bank SMS against a real model: {@code ./gradlew liveAiTest} with
 * {@code ANTHROPIC_API_KEY} set (and optionally {@code KARNAME_EVAL_MODEL}). Models are not
 * deterministic, so a small share of misses is tolerated; every miss is printed.
 */
@Tag("live-ai")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class LiveAiEvalTest extends AiTestSupport {

    private static final double REQUIRED = 0.9;
    /** «امروز» in every case: Tuesday 14 Mehr 1405. */
    private static final LocalDate T = TODAY;

    @Autowired
    private CaptureService capture;

    private TestUser user;
    private Account mellat;
    private Account saman;
    private Account dollars;

    record Expected(TransactionType type, String amountToman, LocalDate date, Account account) {
    }

    record Case(String text, List<Expected> expected) {
    }

    @BeforeEach
    void setUp() {
        user = createUser("eval");
        mellat = accountService.create(user.id(), new AccountRequest("کارت ملت", AccountType.BANK, "IRT", Bank.MELLAT, List.of("4321"),
                null, null, null, null, new BigDecimal("80000000"), T.minusMonths(1)));
        saman = accountService.create(user.id(), new AccountRequest("سامان", AccountType.BANK, "IRT", Bank.SAMAN, List.of("5678"),
                null, null, null, null, null, null));
        account(user, "کیف پول نقد", AccountType.CASH, "IRT", "2000000");
        dollars = account(user, "دلار", AccountType.CURRENCY, "USD", null);
        String model = Objects.requireNonNullElse(System.getenv("KARNAME_EVAL_MODEL"), "claude-opus-5-5");
        ProviderView provider = providers.create(new ProviderRequest("Anthropic", AiPreset.ANTHROPIC, null, System.getenv("ANTHROPIC_API_KEY"),
                null, null, null, null, model, null, null, null, null, true));
        providers.saveRoutes(List.of(new RouteRequest(AiTask.EXTRACT, provider.id(), model, null)));
    }

    private static Expected expense(String amount, LocalDate date) {
        return new Expected(TransactionType.EXPENSE, amount, date, null);
    }

    private List<Case> notes() {
        return List.of(
                new Case("دیروز ناهار ۱۸۰ تومن", List.of(expense("180000", T.minusDays(1)))),
                new Case("ناهار ۱۸۰ و اسنپ ۹۵ تومن", List.of(expense("180000", T), expense("95000", T))),
                new Case("اجاره ۱۵ میلیون تومن", List.of(expense("15000000", T))),
                new Case("پنجشنبه ۳۵۰ هزار تومان بنزین زدم", List.of(expense("350000", LocalDate.of(2026, 10, 1)))),
                new Case("۵۰۰۰۰۰ ریال شارژ موبایل", List.of(expense("50000", T))),
                new Case("قهوه ۸۵ تومن از کارت ملت", List.of(new Expected(TransactionType.EXPENSE, "85000", T, mellat))),
                new Case("پریروز ۴۵۰ تومن سوپرمارکت", List.of(expense("450000", T.minusDays(2)))),
                new Case("۱۲۰ هزار تاکسی و ۶۰ تومن پارکینگ", List.of(expense("120000", T), expense("60000", T))),
                new Case("حقوق مهر ۴۵ میلیون واریز شد به سامان", List.of(new Expected(TransactionType.INCOME, "45000000", T, saman))),
                new Case("کرایه‌ی تاکسی ۷۰ تومن", List.of(expense("70000", T))),
                new Case("دو تا نون خریدم ۲۵ تومن", List.of(expense("25000", T))),
                new Case("ماشین رو ۸۰۰ تومن فروختم", List.of(new Expected(TransactionType.INCOME, "800000000", T, null))),
                new Case("۲.۵ میلیون قسط وام دادم", List.of(expense("2500000", T))),
                new Case("دیروز ۱ میلیون و ۲۰۰ هزار خرید لباس", List.of(expense("1200000", T.minusDays(1)))),
                new Case("۱۰۰ دلار خریدم ۱۰ میلیون از کارت ملت",
                        List.of(new Expected(TransactionType.TRANSFER, "10000000", T, mellat))),
                new Case("سلام خوبی؟", List.of()));
    }

    private static final String SMS = """
            بانک ملت
            برداشت:1,250,000
            حساب:***4321
            مانده:12,345,678
            07/13-12:30

            رمز پویا: 523981
            اعتبار ۲ دقیقه

            بانک سامان
            واریز 50,000,000 ریال
            کارت 6219861012345678
            1405/07/10

            *بانک ملت*
            خرید
            کارت6037...4321
            مبلغ:3,450,000ریال
            0712-18:40
            """;

    @Test
    void readsPersianNotes() {
        List<String> misses = new ArrayList<>();
        int checked = 0;
        for (Case c : notes()) {
            checked++;
            List<DraftView> drafts;
            try {
                drafts = capture.quickAdd(user.id(), c.text()).drafts();
            } catch (RuntimeException e) {
                misses.add(c.text() + " → " + e);
                continue;
            }
            String problem = compare(c.expected(), drafts);
            if (problem != null) {
                misses.add(c.text() + " → " + problem);
            }
        }
        report("notes", checked, misses);
    }

    @Test
    void readsBankSms() {
        SmsResult result = capture.sms(user.id(), SMS);
        List<String> misses = new ArrayList<>();
        String problem = compare(List.of(
                new Expected(TransactionType.EXPENSE, "125000", T.minusDays(1), mellat),
                new Expected(TransactionType.INCOME, "5000000", LocalDate.of(2026, 10, 2), saman),
                new Expected(TransactionType.EXPENSE, "345000", T.minusDays(2), mellat)), result.drafts().stream().map(d -> d.draft()).toList());
        if (problem != null) {
            misses.add(problem);
        }
        if (result.ignored().stream().noneMatch(i -> i.message() == 2)) {
            misses.add("the one-time password was not ignored");
        }
        report("sms", 2, misses);
    }

    private String compare(List<Expected> expected, List<DraftView> drafts) {
        if (expected.size() != drafts.size()) {
            return "expected " + expected.size() + " transactions, got " + drafts.size() + ": " + drafts;
        }
        for (int i = 0; i < expected.size(); i++) {
            Expected e = expected.get(i);
            DraftView d = drafts.get(i);
            if (d.type() != e.type() || d.amount() == null || d.amount().compareTo(new BigDecimal(e.amountToman())) != 0
                    || !e.date().equals(d.date()) || (e.account() != null && !e.account().getId().equals(d.accountId()))) {
                return "expected " + e.type() + " " + e.amountToman() + " on " + e.date()
                        + (e.account() == null ? "" : " from " + e.account().getName()) + ", got " + d.type() + " " + d.amount() + " on "
                        + d.date() + " account " + d.accountId();
            }
        }
        return null;
    }

    private static void report(String name, int checked, List<String> misses) {
        System.out.printf("live-ai %s: %d/%d correct%n", name, checked - misses.size(), checked);
        misses.forEach(m -> System.out.println("  miss: " + m));
        assertThat((double) (checked - misses.size()) / checked).as(String.join("\n", misses)).isGreaterThanOrEqualTo(REQUIRED);
    }
}
