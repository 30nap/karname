package ir.karname.io;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Today is 14 Mehr 1405 (2026-10-06). */
class BackupIntegrationTest extends FinanceTestSupport {

    private long created(TestUser user, String url, Map<String, Object> body) throws Exception {
        String json = mvc.perform(postAs(user, url, body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return readJson(json).get("id").asLong();
    }

    /** A bit of everything: custom unit, categories, exchange, loan, recurring rule, cheque, budget, goal, own price, settings. */
    private void seed(TestUser user) throws Exception {
        Account bank = bank(user, "ملت", "50000000");
        Account dollars = account(user, "دلار", AccountType.CURRENCY, "USD", "100");
        String fund = readJson(mvc.perform(postAs(user, "/api/v1/commodities", Map.of("nameFa", "صندوق طلا", "unitFa", "واحد",
                "kind", "SECURITY", "scale", 0))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("code").asString();
        account(user, "صندوق طلای کهربا", AccountType.INVESTMENT, fund, "250");
        long sport = created(user, "/api/v1/categories", new HashMap<>(Map.of("name", "باشگاه", "kind", "EXPENSE",
                "parentId", category(user, "سلامت"))));
        expense(user, bank, "1200000", LocalDate.of(2026, 10, 1), sport, "باشگاه انقلاب");
        transfer(user, bank, dollars, "10400000", "100", LocalDate.of(2026, 10, 2));

        long loan = created(user, "/api/v1/loans", new HashMap<>(Map.of("name", "وام خودرو", "principal", "100000000", "annualRate", "18",
                "termMonths", 36, "firstDueDate", "2026-09-22", "start", "NEW", "depositAccountId", bank.getId(),
                "receivedOn", "2026-08-23", "paymentAccountId", bank.getId())));
        mvc.perform(postAs(user, "/api/v1/loans/{id}/installments/1/payment", Map.of(), loan)).andExpect(status().isOk());

        long rule = created(user, "/api/v1/recurring", new HashMap<>(Map.of("name", "اجاره", "type", "EXPENSE", "accountId", bank.getId(),
                "amount", "18000000", "frequency", "MONTHLY", "dayOfMonth", 3, "startDate", "2026-08-25", "mode", "REMIND")));
        mvc.perform(postAs(user, "/api/v1/recurring/{id}/occurrences/2026-09-25/post", Map.of(), rule)).andExpect(status().isOk());
        mvc.perform(postAs(user, "/api/v1/recurring/{id}/occurrences/2026-08-25/skip", Map.of(), rule)).andExpect(status().isNoContent());

        long cheque = created(user, "/api/v1/cheques", new HashMap<>(Map.of("direction", "ISSUED", "accountId", bank.getId(),
                "counterparty", "فروشگاه", "amount", "5000000", "dueDate", "2026-10-04", "sayadId", "1234567890123456")));
        mvc.perform(postAs(user, "/api/v1/cheques/{id}/status", Map.of("status", "CLEARED", "date", "2026-10-04"), cheque))
                .andExpect(status().isOk());

        mvc.perform(putAs(user, "/api/v1/budgets/1405-07/{c}", Map.of("amount", "3000000"), sport)).andExpect(status().isOk());
        created(user, "/api/v1/goals", new HashMap<>(Map.of("name", "سفر", "targetAmount", "2000", "commodity", "USD",
                "accountIds", List.of(dollars.getId()))));
        mvc.perform(postAs(user, "/api/v1/prices", Map.of("commodity", "USD", "priceToman", "105000"))).andExpect(status().isCreated());
        mvc.perform(putAs(user, "/api/v1/settings", Map.of("displayUnit", "RIAL", "digitStyle", "PERSIAN", "theme", "DARK",
                "wealthUnits", List.of("USD"), "aiEnabled", true, "aiShareDescriptions", false))).andExpect(status().isOk());
    }

    private String get(TestUser user, String url) throws Exception {
        return mvc.perform(getAs(user, url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** What the user sees, without ids: equal fingerprints mean nothing was lost or changed. */
    private List<String> fingerprint(TestUser user) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode a : readJson(get(user, "/api/v1/accounts?includeArchived=true"))) {
            out.add("account " + a.get("name").asString() + " " + a.get("type").asString() + " " + a.get("balance").asString());
        }
        for (JsonNode t : readJson(get(user, "/api/v1/transactions?size=200")).get("items")) {
            JsonNode category = t.get("category");
            out.add("tx " + t.get("date").asString() + " " + t.get("type").asString() + " " + t.get("amount").asString() + " "
                    + t.get("account").get("name").asString() + " " + (category == null || category.isNull() ? "-" : category.get("name").asString())
                    + " " + t.get("source").asString() + " " + t.path("description").asString(""));
        }
        for (JsonNode l : readJson(get(user, "/api/v1/loans"))) {
            out.add("loan " + l.get("name").asString() + " " + l.get("outstanding").asString() + " paid " + l.get("paidCount").asInt());
        }
        for (JsonNode r : readJson(get(user, "/api/v1/recurring"))) {
            out.add("rule " + r.get("name").asString() + " next " + r.path("nextDate").asString("") + " due " + r.get("dueCount").asInt());
        }
        out.add("pending " + readJson(get(user, "/api/v1/recurring/pending")).size());
        for (JsonNode c : readJson(get(user, "/api/v1/cheques"))) {
            out.add("cheque " + c.get("counterparty").asString() + " " + c.get("status").asString() + " tx " + c.hasNonNull("transactionId"));
        }
        for (JsonNode b : readJson(get(user, "/api/v1/budgets?month=1405-07")).get("items")) {
            out.add("budget " + b.get("name").asString() + " " + b.get("amount").asString() + " spent " + b.get("spent").asString());
        }
        for (JsonNode g : readJson(get(user, "/api/v1/goals"))) {
            out.add("goal " + g.get("name").asString() + " " + g.path("currentAmount").asString("") + " accounts " + g.get("accountIds").size());
        }
        for (JsonNode p : readJson(get(user, "/api/v1/prices?commodity=USD"))) {
            out.add("price " + p.get("priceToman").asString() + " " + p.get("source").asString() + " " + p.get("personal").asBoolean());
        }
        out.add("categories " + readJson(get(user, "/api/v1/categories")).size());
        out.add("suggest " + readJson(get(user, "/api/v1/categories/suggest?description=باشگاه انقلاب")).hasNonNull("categoryId"));
        JsonNode settings = readJson(get(user, "/api/v1/settings"));
        out.add("settings " + settings.get("displayUnit").asString() + " " + settings.get("theme").asString() + " " + settings.get("aiShareDescriptions"));
        return out;
    }

    private MockMultipartHttpServletRequestBuilder restore(TestUser user, String content) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/io/restore");
        request.file(new MockMultipartFile("file", "backup.json", "application/json", content.getBytes(StandardCharsets.UTF_8)));
        request.with(as(user)).with(xsrf());
        return request;
    }

    @Test
    void restoringABackupBringsEverythingBack() throws Exception {
        TestUser user = createUser("sina");
        seed(user);
        List<String> before = fingerprint(user);
        String backup = mvc.perform(getAs(user, "/api/v1/io/backup"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("karname-backup-1405-07-14.json")))
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(backup).get("format").asString()).isEqualTo("karname-backup");
        assertThat(backup).doesNotContain("\"user_id\"");

        // later changes are undone by the restore
        Account extra = bank(user, "حساب اضافه", "1000");
        mvc.perform(deleteAs(user, "/api/v1/accounts/{id}?force=true", extra.getId())).andExpect(status().isNoContent());
        bank(user, "حساب جدید", "2000");
        assertThat(fingerprint(user)).isNotEqualTo(before);

        mvc.perform(restore(user, backup).param("confirm", "REPLACE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.loans").value(1))
                .andExpect(jsonPath("$.rows.loan_installments").value(36));
        assertThat(fingerprint(user)).isEqualTo(before);

        // a paid installment and a posted occurrence are still recognised through their rewritten references
        String loans = get(user, "/api/v1/loans");
        long loanId = readJson(loans).get(0).get("id").asLong();
        mvc.perform(postAs(user, "/api/v1/loans/{id}/installments/1/payment", Map.of(), loanId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("loan.alreadyPaid"));
    }

    @Test
    void aBackupMovesDataToAnotherAccount() throws Exception {
        TestUser sina = createUser("sina");
        TestUser maryam = createUser("maryam");
        seed(sina);
        List<String> sinas = fingerprint(sina);
        // a backup holds only its owner's data
        JsonNode empty = readJson(get(maryam, "/api/v1/io/backup")).get("tables");
        assertThat(empty.get("accounts").size() + empty.get("transactions").size() + empty.get("loans").size()).isZero();
        String backup = get(sina, "/api/v1/io/backup");
        mvc.perform(restore(maryam, backup).param("confirm", "REPLACE")).andExpect(status().isOk());
        assertThat(fingerprint(maryam)).isEqualTo(sinas);
        assertThat(fingerprint(sina)).isEqualTo(sinas);
    }

    @Test
    void aBadFileChangesNothing() throws Exception {
        TestUser user = createUser("sina");
        seed(user);
        List<String> before = fingerprint(user);
        String backup = get(user, "/api/v1/io/backup");

        mvc.perform(restore(user, backup)).andExpect(jsonPath("$.code").value("backup.confirmRequired"));
        mvc.perform(restore(user, "{\"format\": \"other\"}").param("confirm", "REPLACE")).andExpect(jsonPath("$.code").value("backup.invalidFormat"));
        mvc.perform(restore(user, "not json").param("confirm", "REPLACE")).andExpect(jsonPath("$.code").value("backup.invalidFormat"));

        ObjectNode tampered = (ObjectNode) readJson(backup);
        ((ObjectNode) tampered.get("tables").get("transactions").get(0)).put("account_id", 99_999_999L);
        mvc.perform(restore(user, tampered.toString()).param("confirm", "REPLACE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("backup.invalidReference"));

        ObjectNode injected = (ObjectNode) readJson(backup);
        ((ObjectNode) injected.get("tables").get("accounts").get(0)).put("name; DROP TABLE users", "x");
        mvc.perform(restore(user, injected.toString()).param("confirm", "REPLACE"))
                .andExpect(jsonPath("$.code").value("backup.unknownColumn"));

        // a custom unit posing as a built-in one would break every lookup of the real one
        ObjectNode shadowing = (ObjectNode) readJson(backup);
        ObjectNode tables = (ObjectNode) shadowing.get("tables");
        ArrayNode units = tables.has("commodities") ? (ArrayNode) tables.get("commodities") : tables.putArray("commodities");
        units.addObject().put("code", "USD").put("name_fa", "دلار تقلبی");
        mvc.perform(restore(user, shadowing.toString()).param("confirm", "REPLACE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("backup.invalidCommodity"));

        assertThat(fingerprint(user)).isEqualTo(before);
    }

    @Test
    void transactionsExportAsCsvForSpreadsheets() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000000");
        expense(user, bank, "250000", LocalDate.of(2026, 10, 1), category(user, "سوپرمارکت"), "=cmd|' /C calc'!A0");
        byte[] csv = mvc.perform(getAs(user, "/api/v1/io/transactions.csv?from=2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.matchesPattern("text/csv; ?charset=UTF-8")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(csv).startsWith(0xEF, 0xBB, 0xBF);
        String text = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertThat(text.lines()).hasSize(2);
        assertThat(text.lines().findFirst().orElseThrow()).startsWith("تاریخ,تاریخ میلادی,نوع,حساب,مبلغ,واحد");
        assertThat(text.lines().skip(1).findFirst().orElseThrow())
                .isEqualTo("1405/07/09,2026-10-01,هزینه,ملت,250000,IRT,,,,,خوراک و خواربار / سوپرمارکت,'=cmd|' /C calc'!A0,,,دستی");
    }
}
