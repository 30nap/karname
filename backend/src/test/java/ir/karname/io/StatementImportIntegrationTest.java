package ir.karname.io;

import ir.karname.account.Account;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Today is 14 Mehr 1405 (2026-10-06); 10 Mehr is 2026-10-02. */
class StatementImportIntegrationTest extends FinanceTestSupport {

    private static final String STATEMENT = """
            تاریخ,شرح,برداشت (ریال),واریز (ریال),مانده
            1405/07/10,خرید هایپراستار ونک,"1,250,000",,"8,750,000"
            1405/07/10,خرید هایپراستار ونک,"1,250,000",,"7,500,000"
            ۱۴۰۵/۰۷/۱۱,واریز حقوق,,"680,000,000","687,500,000"
            1405/07/12,کارمزد انتقال,"4,500",,"687,495,500"
            1405/07/13,اقساط,abc,,
            دیروز,بی‌تاریخ,"1,000",,
            """;

    private MockMultipartHttpServletRequestBuilder upload(TestUser user, String url, String content) {
        MockMultipartHttpServletRequestBuilder request = multipart(url);
        request.file(new MockMultipartFile("file", "statement.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)));
        request.with(as(user)).with(xsrf());
        return request;
    }

    private JsonNode preview(TestUser user, Account account) throws Exception {
        String json = mvc.perform(upload(user, "/api/v1/io/import/preview", STATEMENT).param("accountId", account.getId().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readJson(json);
    }

    @Test
    void previewsRowsWithCategoriesAndDuplicates() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "10000000");
        long supermarket = category(user, "سوپرمارکت");
        // a purchase entered by hand, and a learned merchant
        expense(user, bank, "125000", LocalDate.of(2026, 10, 2), supermarket, "هایپراستار");

        JsonNode preview = preview(user, bank);
        JsonNode mapping = preview.get("mapping");
        assertThat(List.of(mapping.get("date").asInt(), mapping.get("description").asInt(),
                mapping.get("debit").asInt(), mapping.get("credit").asInt())).containsExactly(0, 1, 2, 3);
        assertThat(mapping.get("unit").asString()).isEqualTo("RIAL");
        assertThat(mapping.get("hasHeader").asBoolean()).isTrue();

        JsonNode rows = preview.get("rows");
        assertThat(rows.size()).isEqualTo(6);
        JsonNode first = rows.get(0);
        assertThat(first.get("line").asInt()).isEqualTo(2);
        assertThat(first.get("date").asString()).isEqualTo("2026-10-02");
        assertThat(first.get("amount").asString()).isEqualTo("-125000");
        assertThat(first.get("categoryId").asLong()).isEqualTo(supermarket);
        assertThat(first.get("duplicate").asBoolean()).isTrue();
        // the same purchase twice in one file stays two rows
        assertThat(rows.get(1).get("ref").asString()).isNotEqualTo(first.get("ref").asString());
        assertThat(rows.get(2).get("amount").asString()).isEqualTo("68000000");
        assertThat(rows.get(2).get("date").asString()).isEqualTo("2026-10-03");
        assertThat(rows.get(2).get("duplicate").asBoolean()).isFalse();
        assertThat(rows.get(3).get("amount").asString()).isEqualTo("-450");
        assertThat(rows.get(4).get("error").asString()).isEqualTo("مبلغ خوانده نشد.");
        assertThat(rows.get(5).get("error").asString()).isEqualTo("تاریخ «دیروز» خوانده نشد.");
    }

    @Test
    void commitsChosenRowsOnce() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "10000000");
        long salary = category(user, "حقوق و دستمزد");
        JsonNode rows = preview(user, bank).get("rows");

        List<Map<String, Object>> chosen = new ArrayList<>();
        for (int i : new int[] {1, 2, 3}) {
            JsonNode r = rows.get(i);
            Map<String, Object> row = new HashMap<>(Map.of("date", r.get("date").asString(), "amount", r.get("amount").asString(),
                    "description", r.get("description").asString(), "ref", r.get("ref").asString()));
            if (i == 2) {
                row.put("categoryId", salary);
            }
            chosen.add(row);
        }
        Map<String, Object> body = Map.of("accountId", bank.getId(), "rows", chosen);
        mvc.perform(postAs(user, "/api/v1/io/import/commit", body))
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.skipped").value(0));
        mvc.perform(postAs(user, "/api/v1/io/import/commit", body))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(3));

        mvc.perform(getAs(user, "/api/v1/transactions?source=IMPORT"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].type").value("EXPENSE"))
                .andExpect(jsonPath("$.items[0].amount").value("450"))
                .andExpect(jsonPath("$.items[1].type").value("INCOME"))
                .andExpect(jsonPath("$.items[1].category.name").value("حقوق و دستمزد"));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("77874550"));
        // imported rows are recognised in the next preview
        JsonNode again = preview(user, bank).get("rows");
        assertThat(again.get(1).get("duplicate").asBoolean()).isTrue();
        assertThat(again.get(2).get("duplicate").asBoolean()).isTrue();
    }

    @Test
    void columnsCanBeChosenWhenThereIsNoHeader() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", null);
        String headerless = "2026-10-01;پرداخت قبض;-320000\n2026-10-02;بازپرداخت;15000\n";
        mvc.perform(upload(user, "/api/v1/io/import/preview", headerless).param("accountId", bank.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("import.mappingRequired"));
        mvc.perform(upload(user, "/api/v1/io/import/preview", headerless).param("accountId", bank.getId().toString())
                        .param("dateColumn", "0").param("descriptionColumn", "1").param("amountColumn", "2").param("unit", "TOMAN")
                        .param("hasHeader", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].amount").value("-320000"))
                .andExpect(jsonPath("$.rows[1].amount").value("15000"))
                .andExpect(jsonPath("$.rows[1].date").value("2026-10-02"));
    }

    @Test
    void importsOnlyIntoOwnAccounts() throws Exception {
        TestUser owner = createUser("owner");
        TestUser other = createUser("other");
        Account bank = bank(owner, "ملت", null);
        mvc.perform(upload(other, "/api/v1/io/import/preview", STATEMENT).param("accountId", bank.getId().toString()))
                .andExpect(status().isNotFound());
        Map<String, Object> body = Map.of("accountId", bank.getId(), "rows",
                List.of(Map.of("date", "2026-10-02", "amount", "-1000", "description", "x", "ref", "import:0123456789abcdef0123456789abcdef")));
        mvc.perform(postAs(other, "/api/v1/io/import/commit", body)).andExpect(status().isNotFound());
        mvc.perform(postAs(owner, "/api/v1/io/import/commit", Map.of("accountId", bank.getId(), "rows",
                List.of(Map.of("date", "2026-10-02", "amount", "-1000", "ref", "loan:1:1:p"))))).andExpect(status().isBadRequest());
    }
}
