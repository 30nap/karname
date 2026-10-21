package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.ai.llm.Part;
import ir.karname.ai.tools.FinanceTools;
import ir.karname.ai.tools.FinanceTools.Outcome;
import ir.karname.ai.tools.FinanceTools.ToolContext;
import ir.karname.common.web.ApiException;
import ir.karname.account.BalanceService;
import ir.karname.loan.LoanMethod;
import ir.karname.loan.LoanService;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assistant proposes; the user decides. A proposal runs the app's own service in a transaction
 * that is rolled back, so the card's figures are the service's and nothing is left behind; only the
 * user's confirmation records it, once.
 */
class ProposalIntegrationTest extends FinanceTestSupport {

    @Autowired
    private FinanceTools tools;

    @Autowired
    private ProposalService proposals;

    @Autowired
    private LoanService loans;

    @Autowired
    private BalanceService balances;

    private TestUser user;
    private Account salary;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        user = createUser("sina");
        salary = bank(user, "ملت حقوق", "300000000");
        ctx = context(user, conversation(user));
    }

    private long conversation(TestUser owner) {
        return jdbc.sql("INSERT INTO ai_conversations (user_id, title, provider_kind, model) VALUES (:u, 'گفتگو', 'FAKE', 'm') RETURNING id")
                .param("u", owner.id()).query(Long.class).single();
    }

    private static ToolContext context(TestUser owner, Long conversation) {
        return new ToolContext(owner.id(), TODAY, ZoneId.of("Asia/Tehran"), true, null, conversation, 1, FinanceTools.CURRENT_VERSION);
    }

    private Outcome call(ToolContext context, String tool, String input) {
        return tools.run(context, new Part.ToolCall("call_1", tool, input));
    }

    /** Proposes and returns the views of the cards shown. */
    private List<ProposalView> propose(String tool, String input) {
        Outcome outcome = call(ctx, tool, input);
        assertThat(outcome.error()).as(outcome.content()).isFalse();
        List<String> refs = new ArrayList<>();
        readJson(outcome.data()).get("proposals").forEach(n -> refs.add(n.asString()));
        return refs.stream().map(r -> proposals.views(user.id(), List.of(r)).get(r)).toList();
    }

    private String problem(String tool, String input) {
        Outcome outcome = call(ctx, tool, input);
        assertThat(outcome.error()).as(outcome.content()).isTrue();
        assertThat(outcome.data()).isNull();
        return outcome.content();
    }

    private static String line(ProposalView view, String label) {
        return view.lines().stream().filter(l -> l.label().equals(label)).map(Card.Line::value).findFirst()
                .orElseThrow(() -> new AssertionError("No line «" + label + "» in " + view.lines()));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = :u").param("u", user.id()).query(Long.class).single();
    }

    private BigDecimal balance(long accountId) {
        return balances.balance(user.id(), accountId, TODAY);
    }

    @Test
    void aLoanIsWorkedOutByTheAppButRecordedOnlyWhenTheUserConfirms() {
        long accounts = count("accounts");
        long transactions = count("transactions");
        Outcome outcome = call(ctx, "propose_loan", """
                {"name":"وام مسکن","bank":"MELLI","principal":200,"principal_scale":"MILLION","annual_rate_percent":23,
                 "term_months":36,"first_due_date":"1405/08/14","deposit_account":"حقوق"}""");
        assertThat(outcome.error()).as(outcome.content()).isFalse();
        JsonNode content = readJson(outcome.content());
        assertThat(content.get("shown_to_user").asInt()).isEqualTo(1);
        assertThat(content.get("note").asString()).contains("nothing is recorded until they confirm");

        // the installment is the loan service's own, not the model's
        BigDecimal installment = loans.preview(new BigDecimal("200000000"), new BigDecimal("23"), 36, LoanMethod.ANNUITY, null,
                LocalDate.of(2026, 11, 5)).firstInstallment();
        String ref = readJson(outcome.data()).get("proposals").get(0).asString();
        ProposalView card = proposals.views(user.id(), List.of(ref)).get(ref);
        assertThat(card.status()).isEqualTo("PENDING");
        assertThat(card.title()).isEqualTo("وام مسکن");
        assertThat(new BigDecimal(line(card, "مبلغ هر قسط"))).isEqualByComparingTo(installment);
        assertThat(line(card, "مبلغ وام")).isEqualTo("200000000");
        assertThat(line(card, "واریز به")).isEqualTo("ملت حقوق");
        assertThat(card.lines().stream().filter(Card.Line::main).map(Card.Line::label)).containsExactly("مبلغ وام");
        assertThat(card.effects()).anyMatch(e -> e.contains("به «ملت حقوق» منتقل می‌شود"));
        assertThat(content.at("/cards/0/details/مبلغ هر قسط").asString()).endsWith(" تومان");

        // the check ran and was rolled back
        assertThat(count("loans")).isZero();
        assertThat(count("accounts")).isEqualTo(accounts);
        assertThat(count("transactions")).isEqualTo(transactions);

        ProposalView applied = proposals.apply(user.id(), ref);
        assertThat(applied.status()).isEqualTo("APPLIED");
        long loanId = jdbc.sql("SELECT id FROM loans WHERE user_id = :u").param("u", user.id()).query(Long.class).single();
        assertThat(applied.link()).isEqualTo("/loans/" + loanId);
        assertThat(balance(salary.getId())).isEqualByComparingTo("500000000");
        assertThat(loans.get(user.id(), loanId).installments()).hasSize(36);

        // a second click, or a retry, records nothing more
        assertThat(proposals.apply(user.id(), ref).link()).isEqualTo(applied.link());
        assertThat(count("loans")).isEqualTo(1);
        assertThat(balance(salary.getId())).isEqualByComparingTo("500000000");
    }

    @Test
    void twoConfirmationsAtOnceRecordOnce() throws Exception {
        String ref = propose("propose_account", """
                {"name":"صندوق خانه","type":"CASH","opening_balance":5,"opening_balance_scale":"MILLION"}""").getFirst().ref();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ProposalView>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return proposals.apply(user.id(), ref);
                }));
            }
            start.countDown();
            for (Future<ProposalView> r : results) {
                assertThat(r.get().status()).isEqualTo("APPLIED");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM accounts WHERE user_id = :u AND name = 'صندوق خانه'").param("u", user.id())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aLoanAlreadyBeingRepaidCountsWhatIsDueAndItsNextInstallmentCanBePaid() {
        ProposalView card = propose("propose_loan", """
                {"principal":120,"principal_scale":"MILLION","annual_rate_percent":4,"term_months":24,"first_due_date":"1405/04/14"}""")
                .getFirst();
        // due on 14 Tir, Mordad, Shahrivar and Mehr (today): four, assumed paid and said so
        assertThat(line(card, "اقساط پرداخت‌شده تا امروز")).isEqualTo("4");
        assertThat(card.warnings()).anyMatch(w -> w.contains("4 قسطی"));
        assertThat(card.title()).isEqualTo("وام");
        proposals.apply(user.id(), card.ref());

        ProposalView payment = propose("propose_loan_payment", """
                {"loan":"وام","account":"ملت حقوق"}""").getFirst();
        assertThat(payment.title()).isEqualTo("قسط 5 «وام»");
        assertThat(line(payment, "سررسید")).isEqualTo("2026-11-05");
        assertThat(count("transactions")).isEqualTo(2); // the bank's and the loan's opening balances only
        ProposalView paid = proposals.apply(user.id(), payment.ref());
        assertThat(paid.status()).isEqualTo("APPLIED");
        long loanId = jdbc.sql("SELECT id FROM loans WHERE user_id = :u").param("u", user.id()).query(Long.class).single();
        assertThat(loans.get(user.id(), loanId).paidCount()).isEqualTo(5);
        assertThat(problem("propose_loan_payment", "{\"loan\":\"وام\",\"installment_number\":5}")).contains("پرداخت شده");
    }

    @Test
    void anAccountInAnotherUnitTakesItsBalanceInThatUnit() {
        assertThat(problem("propose_account", """
                {"name":"دلار نقد","type":"CURRENCY","opening_balance":1500}""")).contains("kept in USD");
        ProposalView card = propose("propose_account", """
                {"name":"دلار نقد","type":"CURRENCY","opening_balance":1500,"opening_balance_unit":"ACCOUNT"}""").getFirst();
        Card.Line held = card.lines().stream().filter(l -> l.label().equals("موجودی")).findFirst().orElseThrow();
        assertThat(held.value()).isEqualTo("1500");
        assertThat(held.unit()).isEqualTo("USD");
        assertThat(card.warnings()).anyMatch(w -> w.contains("قیمتی ثبت نشده"));
        ProposalView applied = proposals.apply(user.id(), card.ref());
        long id = Long.parseLong(applied.link().substring("/accounts/".length()));
        assertThat(balance(id)).isEqualByComparingTo("1500");
    }

    @Test
    void namesResolveToTheUsersOwnRecordsOrComeBackWithCandidates() {
        Account savings = bank(user, "ملت پس‌انداز", "1000000");
        String ambiguous = problem("propose_cheque", """
                {"direction":"ISSUED","amount":12,"amount_scale":"MILLION","due_date":"1405/08/01","account":"ملت"}""");
        assertThat(ambiguous).contains("several accounts").contains(salary.getId() + " «ملت حقوق»").contains(savings.getId() + " «ملت پس‌انداز»");
        assertThat(problem("propose_cheque", """
                {"direction":"ISSUED","amount":12,"amount_scale":"MILLION","due_date":"1405/08/01","account":"تجارت"}"""))
                .contains("no account matches");
        assertThat(problem("propose_cheque", """
                {"direction":"ISSUED","amount":12,"amount_scale":"MILLION","due_date":"1405/08/01","category":"کاشت درخت روی ماه"}"""))
                .contains("no EXPENSE category matches").contains("رستوران");

        // another user's account is not even a candidate
        TestUser other = createUser("other");
        Account foreign = bank(other, "ملت حقوق", "1");
        assertThat(problem("propose_cheque", """
                {"direction":"ISSUED","amount":12,"amount_scale":"MILLION","due_date":"1405/08/01","account":"%d"}""".formatted(foreign.getId())))
                .doesNotContain("«ملت حقوق» cannot");

        ProposalView card = propose("propose_cheque", """
                {"direction":"ISSUED","amount":12,"amount_scale":"MILLION","due_date":"1405/08/01","account":"پس انداز",
                 "counterparty":"علی رضایی","category":"رستوران","sayad_id":"1234567890123456"}""").getFirst();
        assertThat(line(card, "از حساب")).isEqualTo("ملت پس‌انداز");
        assertThat(line(card, "شناسه‌ی صیادی")).isEqualTo("1234567890123456");
        assertThat(card.title()).isEqualTo("چک صادره به علی رضایی");
    }

    @Test
    void aChequeAndItsClearing() {
        ProposalView cheque = propose("propose_cheque", """
                {"direction":"ISSUED","amount":12,"amount_scale":"MILLION","due_date":"1405/08/01","counterparty":"صاحبخانه",
                 "category":"رستوران"}""").getFirst();
        assertThat(cheque.warnings()).anyMatch(w -> w.contains("«ملت حقوق» در نظر گرفته شد"));
        proposals.apply(user.id(), cheque.ref());
        assertThat(count("cheques")).isEqualTo(1);

        ProposalView cleared = propose("propose_cheque_status", """
                {"cheque":"صاحبخانه","status":"CLEARED"}""").getFirst();
        assertThat(line(cleared, "وضعیت فعلی")).isEqualTo("در انتظار");
        assertThat(cleared.effects()).anyMatch(e -> e.contains("هزینه از «ملت حقوق»"));
        assertThat(balance(salary.getId())).isEqualByComparingTo("300000000");
        proposals.apply(user.id(), cleared.ref());
        assertThat(balance(salary.getId())).isEqualByComparingTo("288000000");
        assertThat(problem("propose_cheque_status", "{\"cheque\":\"صاحبخانه\",\"status\":\"CLEARED\"}")).contains("already CLEARED");
    }

    @Test
    void budgetsAreACardEachAndAnItemThatCannotBeUsedGoesBackToTheModel() {
        Outcome outcome = call(ctx, "propose_budgets", """
                {"budgets":[{"category":"رستوران","amount":5,"amount_scale":"MILLION"},
                            {"category":"کاشت درخت روی ماه","amount":1,"amount_scale":"MILLION"}]}""");
        assertThat(outcome.error()).isFalse();
        JsonNode content = readJson(outcome.content());
        assertThat(content.get("shown_to_user").asInt()).isEqualTo(1);
        assertThat(content.at("/rejected/0/item").asInt()).isEqualTo(2);
        assertThat(content.at("/rejected/0/problem").asString()).contains("no EXPENSE category matches");
        assertThat(count("budgets")).isZero();

        String ref = readJson(outcome.data()).get("proposals").get(0).asString();
        ProposalView card = proposals.views(user.id(), List.of(ref)).get(ref);
        assertThat(card.title()).isEqualTo("بودجه‌ی «رستوران و کافه › رستوران»، مهر ۱۴۰۵");
        assertThat(line(card, "دوره")).isEqualTo("از این ماه به بعد");
        proposals.apply(user.id(), ref);
        assertThat(jdbc.sql("SELECT amount_toman FROM budgets WHERE user_id = :u").param("u", user.id()).query(BigDecimal.class).single())
                .isEqualByComparingTo("5000000");
    }

    @Test
    void aRecurringSalaryIsRemindedUnlessAutomaticWasAskedFor() {
        ProposalView card = propose("propose_recurring", """
                {"name":"حقوق","type":"INCOME","amount":60,"amount_scale":"MILLION","account":"ملت حقوق","frequency":"MONTHLY",
                 "day_of_month":25}""").getFirst();
        assertThat(line(card, "ثبت")).isEqualTo("با یادآوری و تأیید شما");
        assertThat(line(card, "تکرار")).isEqualTo("هر ماه، روز 25");
        assertThat(line(card, "سه نوبت بعدی").split("، ")).hasSize(3);
        assertThat(card.warnings()).isEmpty();
        ProposalView automatic = propose("propose_recurring", """
                {"name":"اجاره","type":"EXPENSE","amount":20,"amount_scale":"MILLION","frequency":"MONTHLY","automatic":true}""").getFirst();
        assertThat(automatic.warnings()).anyMatch(w -> w.contains("بدون تأیید"));
        assertThat(count("recurring_rules")).isZero();
        proposals.apply(user.id(), card.ref());
        assertThat(count("recurring_rules")).isEqualTo(1);
    }

    @Test
    void aGoalInEurosAndPricesAndCategories() {
        globalPrice("EUR", "120000", TODAY.atStartOfDay(ZoneId.of("Asia/Tehran")).toInstant());
        ProposalView goal = propose("propose_goal", """
                {"name":"صندوق مهاجرت","target":15,"target_scale":"THOUSAND","unit":"یورو","target_date":"1406/12/29","accounts":["حقوق"]}""")
                .getFirst();
        Card.Line target = goal.lines().stream().filter(l -> l.label().equals("مبلغ هدف")).findFirst().orElseThrow();
        assertThat(target.value()).isEqualTo("15000");
        assertThat(target.unit()).isEqualTo("EUR");
        assertThat(line(goal, "از حساب‌های")).isEqualTo("«ملت حقوق»");
        assertThat(line(goal, "پس‌انداز فعلی")).isEqualTo("2500");
        proposals.apply(user.id(), goal.ref());
        assertThat(count("goals")).isEqualTo(1);

        ProposalView price = propose("propose_price", "{\"unit\":\"EUR\",\"price\":300000}").getFirst();
        assertThat(price.warnings()).anyMatch(w -> w.contains("بیش از ۵۰٪"));
        assertThat(line(price, "تغییر")).isEqualTo("150");
        proposals.apply(user.id(), price.ref());
        assertThat(jdbc.sql("SELECT count(*) FROM prices WHERE user_id = :u AND source = 'MANUAL'").param("u", user.id()).query(Long.class)
                .single()).isEqualTo(1);
        assertThat(problem("propose_price", "{\"unit\":\"تومان\",\"price\":1}")).contains("no price");

        ProposalView category = propose("propose_category", "{\"name\":\"تیراندازی با کمان\",\"kind\":\"EXPENSE\"}").getFirst();
        assertThat(categoryService.list(user.id())).noneMatch(c -> c.getName().equals("تیراندازی با کمان"));
        proposals.apply(user.id(), category.ref());
        assertThat(categoryService.list(user.id())).anyMatch(c -> c.getName().equals("تیراندازی با کمان"));
    }

    @Test
    void aServiceRefusalGoesBackToTheModelAndNothingIsShown() {
        // the loan service accepts a first due date at most ten years ahead
        assertThat(problem("propose_loan", """
                {"principal":10,"principal_scale":"MILLION","annual_rate_percent":10,"term_months":12,"first_due_date":"1420/01/01"}"""))
                .contains("تاریخ");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_proposals").query(Long.class).single()).isZero();
        assertThat(problem("propose_account", "{\"name\":\"x\",\"type\":\"LOAN\",\"opening_balance\":0}")).contains("type");
    }

    @Test
    void onlyItsOwnerDecidesAndOnlyWhileItIsPending() {
        String ref = propose("propose_category", "{\"name\":\"تیراندازی با کمان\",\"kind\":\"EXPENSE\"}").getFirst().ref();
        TestUser other = createUser("other");
        assertThatThrownBy(() -> proposals.apply(other.id(), ref)).isInstanceOf(ApiException.class).hasMessageContaining("ai.proposalNotFound");
        assertThatThrownBy(() -> proposals.apply(user.id(), "not-a-ref")).isInstanceOf(ApiException.class)
                .hasMessageContaining("ai.proposalNotFound");

        assertThat(proposals.dismiss(user.id(), ref).status()).isEqualTo("DISMISSED");
        assertThatThrownBy(() -> proposals.apply(user.id(), ref)).isInstanceOf(ApiException.class).hasMessageContaining("ai.proposalDismissed");

        String later = propose("propose_category", "{\"name\":\"شطرنج آنلاین\",\"kind\":\"EXPENSE\"}").getFirst().ref();
        clock.advance(Duration.ofDays(8));
        assertThat(proposals.views(user.id(), List.of(later)).get(later).status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> proposals.apply(user.id(), later)).isInstanceOf(ApiException.class).hasMessageContaining("ai.proposalExpired");
        assertThat(categoryService.list(user.id())).noneMatch(c -> c.getName().equals("تیراندازی با کمان")
                || c.getName().equals("شطرنج آنلاین"));
    }

    @Test
    void aCorrectedCardReplacesTheOneNotConfirmed() {
        String first = propose("propose_category", "{\"name\":\"باشگا\",\"kind\":\"EXPENSE\"}").getFirst().ref();
        String second = propose("propose_category", "{\"name\":\"باشگاه\",\"kind\":\"EXPENSE\",\"replaces_previous\":true}").getFirst().ref();
        assertThat(proposals.views(user.id(), List.of(first, second))).extractingByKeys(first, second)
                .extracting(ProposalView::status).containsExactly("REPLACED", "PENDING");
        assertThatThrownBy(() -> proposals.apply(user.id(), first)).isInstanceOf(ApiException.class).hasMessageContaining("ai.proposalReplaced");
    }

    @Test
    void nothingIsProposedOutsideAConversation() {
        Outcome outcome = call(context(user, null), "propose_category", "{\"name\":\"باشگاه\",\"kind\":\"EXPENSE\"}");
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("conversation");
    }

    @Test
    void theUserConfirmsThroughTheApi() throws Exception {
        String ref = propose("propose_account", """
                {"name":"کیف پول","type":"EWALLET","bank":"BLU","card_last4":["1234"],"opening_balance":2,"opening_balance_scale":"MILLION"}""")
                .getFirst().ref();
        mvc.perform(postAs(user, "/api/v1/ai/proposals/{ref}/apply", null, ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.startsWith("/accounts/")));
        mvc.perform(postAs(user, "/api/v1/ai/proposals/{ref}/dismiss", null, ref))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("این پیشنهاد پیش‌تر ثبت شده است."));
        TestUser other = createUser("other");
        mvc.perform(postAs(other, "/api/v1/ai/proposals/{ref}/apply", null, ref)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT identifier_hints FROM accounts WHERE user_id = :u AND name = 'کیف پول'").param("u", user.id())
                .query(String.class).single()).contains("1234");
    }
}
