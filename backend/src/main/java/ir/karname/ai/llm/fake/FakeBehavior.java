package ir.karname.ai.llm.fake;

import ir.karname.ai.llm.LlmMessage;
import ir.karname.ai.llm.LlmRequest;
import ir.karname.ai.llm.LlmResponse;
import ir.karname.ai.llm.Part;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.persian.PersianText;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What the offline model does unscripted: plausible, deterministic answers built from the data in
 * the request, so the whole app can be demonstrated and tested end to end without a provider. It
 * reads the same prompts and tool results a real model gets, but understands only simple Persian.
 */
final class FakeBehavior {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TODAY = Pattern.compile("\\((\\d{4}/\\d{2}/\\d{2})\\)");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.٫]\\d+)?");
    private static final Pattern AMOUNT = Pattern.compile("[-+]?\\d{1,3}(?:[,٬]\\d{3})+|[-+]?\\d{4,}");
    private static final Pattern FULL_DATE = Pattern.compile("(14\\d{2})/(\\d{1,2})/(\\d{1,2})");
    private static final Pattern SHORT_DATE = Pattern.compile("(?<!\\d)(\\d{2})/?(\\d{2})(?:[-\\s]|$)");
    private static final String DEMO_ANSWER = "این پاسخ آزمایشی دستیار است؛ برای تحلیل واقعی، یک سرویس هوش مصنوعی در تنظیمات تعریف کنید.";
    private static final Pattern SMS = Pattern.compile("<sms number=\"(\\d+)\">\\n(.*?)\\n</sms>", Pattern.DOTALL);

    private FakeBehavior() {
    }

    static LlmResponse respond(LlmRequest request) {
        if (request.output() != null) {
            Object answer = switch (request.output().name()) {
                case "transactions" -> quickAdd(request);
                case "bank_sms" -> sms(request);
                case "monthly_report" -> report(request);
                case "categories" -> categories(request);
                default -> FakeLlm.example(request.output().schema());
            };
            return FakeLlm.text(JSON.writeValueAsString(answer));
        }
        List<LlmMessage> messages = request.messages();
        LlmMessage last = messages.isEmpty() ? null : messages.getLast();
        boolean newTurn = last instanceof LlmMessage.User u && u.parts().stream().anyMatch(p -> p instanceof Part.Text);
        if (newTurn && request.tools().stream().anyMatch(t -> t.name().equals("ping"))) {
            return FakeLlm.toolCalls(FakeLlm.call("ping", "{\"word\":\"karname\"}"));
        }
        if (newTurn && !request.tools().isEmpty()) {
            return chooseTool(request, text(last));
        }
        if (last instanceof LlmMessage.User u) {
            for (Part part : u.parts()) {
                if (part instanceof Part.ToolResult r) {
                    return FakeLlm.text(answer(toolName(messages, r.callId()), r.content()));
                }
            }
        }
        return FakeLlm.text(DEMO_ANSWER);
    }

    // ---------------------------------------------------------------- chat

    private static LlmResponse chooseTool(LlmRequest request, String question) {
        String q = PersianText.normalize(PersianText.normalizeDigits(question));
        if (q.contains("ثبت") && NUMBER.matcher(q).find()) {
            List<Map<String, Object>> transactions = parseNote(q, today(request));
            return FakeLlm.toolCalls(FakeLlm.call("propose_transactions", JSON.writeValueAsString(Map.of("transactions", transactions))));
        }
        String tool = q.contains("بودجه") ? "get_budget_status"
                : q.contains("هدف") ? "get_goals"
                : q.contains("قسط") || q.contains("چک") || q.contains("تعهد") ? "get_obligations"
                : q.contains("دسته") || q.contains("کجا") || q.contains("خرج") ? "summarize_by_category"
                : q.contains("دلار") || q.contains("طلا") || q.contains("ثروت") ? "get_net_worth_history"
                : "get_financial_overview";
        if (request.tools().stream().noneMatch(t -> t.name().equals(tool))) {
            return FakeLlm.text(DEMO_ANSWER);
        }
        return FakeLlm.toolCalls(FakeLlm.call(tool, "{}"));
    }

    private static String answer(String tool, String content) {
        JsonNode r = read(content);
        if (r == null || r.has("error")) {
            return "اطلاعات لازم دریافت نشد؛ لطفاً دوباره امتحان کنید.";
        }
        StringBuilder out = new StringBuilder();
        switch (tool) {
            case "get_financial_overview" -> {
                out.append("**دارایی خالص شما ").append(r.path("net_worth_toman").asString("۰")).append(" تومان است.**\n\n");
                out.append("| | این ماه | ماه قبل |\n|---|---|---|\n");
                out.append("| درآمد | ").append(r.at("/this_month_so_far/income_toman").asString("—")).append(" | ")
                        .append(r.at("/last_month/income_toman").asString("—")).append(" |\n");
                out.append("| هزینه | ").append(r.at("/this_month_so_far/expense_toman").asString("—")).append(" | ")
                        .append(r.at("/last_month/expense_toman").asString("—")).append(" |\n");
                if (r.at("/this_month_so_far/savings_rate").isString()) {
                    out.append("\nنرخ پس‌انداز این ماه تا امروز ").append(r.at("/this_month_so_far/savings_rate").asString()).append(" است.");
                }
            }
            case "summarize_by_category" -> {
                out.append("بیشترین هزینه‌های این دوره (جمع ").append(r.path("total_toman").asString("۰")).append(" تومان):\n\n");
                int i = 0;
                for (JsonNode c : r.path("categories")) {
                    if (i++ == 5) {
                        break;
                    }
                    out.append("- **").append(c.path("name").asString()).append("**: ").append(c.path("value_toman").asString())
                            .append(" تومان (").append(c.path("share").asString()).append(")\n");
                }
                if (i == 0) {
                    out.append("در این دوره هزینه‌ای ثبت نشده است.");
                }
            }
            case "get_budget_status" -> {
                JsonNode budgets = r.path("budgets");
                if (budgets.isEmpty()) {
                    out.append("برای این ماه بودجه‌ای تعریف نکرده‌اید؛ از صفحه‌ی بودجه می‌توانید برای دسته‌های پرخرج سقف بگذارید.");
                }
                for (JsonNode b : budgets) {
                    out.append("- ").append(b.path("category").asString()).append(": ").append(b.path("used").asString())
                            .append(" مصرف شده").append("OVER".equals(b.path("status").asString()) ? " — **از بودجه گذشته**" : "").append('\n');
                }
            }
            case "get_goals" -> {
                JsonNode goals = r.path("goals");
                if (goals.isEmpty()) {
                    out.append("هنوز هدفی تعریف نکرده‌اید.");
                }
                for (JsonNode g : goals) {
                    out.append("- **").append(g.path("name").asString()).append("**: ").append(g.path("progress").asString("—"))
                            .append(" پیشرفت").append(g.hasNonNull("estimated_month_reached")
                                    ? "، رسیدن تقریبی در " + g.path("estimated_month_reached").asString() : "").append('\n');
                }
            }
            case "get_obligations" -> {
                out.append("کمترین موجودی نقد پیش رو ").append(r.path("lowest_balance_toman").asString("—")).append(" تومان در ")
                        .append(r.path("lowest_balance_date").asString("—")).append(" است.\n\n");
                int i = 0;
                for (JsonNode e : r.path("events")) {
                    if (i++ == 5) {
                        break;
                    }
                    out.append("- ").append(e.path("date").asString()).append(" | ").append(e.path("title").asString()).append(" | ")
                            .append(e.path("amount_toman").asString()).append(" تومان\n");
                }
            }
            case "get_net_worth_history" -> {
                JsonNode months = r.path("months");
                if (!months.isEmpty()) {
                    JsonNode first = months.get(0);
                    JsonNode lastMonth = months.get(months.size() - 1);
                    out.append("دارایی خالص از ").append(first.path("net_worth_toman").asString()).append(" تومان در ")
                            .append(first.path("month").asString()).append(" به ").append(lastMonth.path("net_worth_toman").asString())
                            .append(" تومان در ").append(lastMonth.path("month").asString()).append(" رسیده است.");
                }
            }
            case "propose_transactions" -> out.append("پیش‌نویس تراکنش‌ها آماده است؛ آن‌ها را پایین همین پیام بررسی و تأیید کنید.");
            default -> out.append("اطلاعات دریافت شد.");
        }
        return out.toString().strip();
    }

    // ---------------------------------------------------------------- quick add

    private static Map<String, Object> quickAdd(LlmRequest request) {
        String note = between(text(request.messages().getFirst()), "<note>", "</note>");
        return Map.of("transactions", parseNote(PersianText.normalize(PersianText.normalizeDigits(note)), today(request)));
    }

    /** «دیروز ناهار ۱۸۰ و اسنپ ۹۵ تومن» → two expenses of 180 and 95 thousand Toman, yesterday. */
    static List<Map<String, Object>> parseNote(String note, JalaliDate today) {
        JalaliDate date = note.contains("پریروز") ? today.plusDays(-2) : note.contains("دیروز") ? today.plusDays(-1) : today;
        String scale = note.contains("میلیارد") ? "BILLION" : note.contains("میلیون") ? "MILLION" : "THOUSAND";
        String unit = note.contains("ریال") ? "RIAL" : "TOMAN";
        boolean income = note.contains("حقوق") || note.contains("واریز") || note.contains("دریافت") || note.contains("گرفتم");
        List<Map<String, Object>> result = new ArrayList<>();
        for (String segment : note.split("\\s+و\\s+|،|,")) {
            Matcher number = NUMBER.matcher(segment);
            if (!number.find()) {
                continue;
            }
            BigDecimal amount = new BigDecimal(number.group().replace('٫', '.'));
            String description = segment.replace(number.group(), " ")
                    .replaceAll("دیروز|پریروز|امروز|تومن|تومان|ریال|هزار|میلیون|میلیارد|ثبت کن|ثبتش کن|خرج کردم|از کارت|،|\\.", " ")
                    .replaceAll("\\s+", " ").strip();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", income ? "INCOME" : "EXPENSE");
            t.put("amount", amount);
            t.put("scale", unit.equals("RIAL") ? "ONE" : amount.compareTo(BigDecimal.valueOf(1000)) >= 0 ? "ONE" : scale);
            t.put("unit", unit);
            t.put("date", date.toString());
            t.put("description", description.isEmpty() ? "هزینه" : description);
            t.put("confidence", "HIGH");
            result.add(t);
        }
        return result;
    }

    // ---------------------------------------------------------------- SMS

    private static Map<String, Object> sms(LlmRequest request) {
        String text = PersianText.normalizeDigits(text(request.messages().getFirst()));
        JalaliDate today = today(request);
        List<Map<String, Object>> transactions = new ArrayList<>();
        List<Map<String, Object>> ignored = new ArrayList<>();
        Matcher m = SMS.matcher(text);
        while (m.find()) {
            int number = Integer.parseInt(m.group(1));
            String body = m.group(2);
            Matcher amount = AMOUNT.matcher(body.replace("****", ""));
            boolean deposit = body.contains("واریز") || body.contains("+");
            boolean withdrawal = body.contains("برداشت") || body.contains("خرید") || body.contains("انتقال") || body.contains("-");
            if (!amount.find() || (!deposit && !withdrawal) || body.contains("رمز")) {
                ignored.add(Map.of("message", number, "reason", "این پیامک تراکنش نیست."));
                continue;
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("message", number);
            t.put("type", deposit && !body.contains("برداشت") ? "INCOME" : "EXPENSE");
            t.put("amount", new BigDecimal(amount.group().replaceAll("[,٬+-]", "")));
            t.put("unit", body.contains("تومان") ? "TOMAN" : "RIAL");
            t.put("date", smsDate(body, today));
            t.put("description", deposit && !body.contains("برداشت") ? "واریز" : body.contains("خرید") ? "خرید" : "برداشت");
            t.put("confidence", "HIGH");
            transactions.add(t);
        }
        return Map.of("transactions", transactions, "ignored", ignored);
    }

    private static String smsDate(String body, JalaliDate today) {
        Matcher full = FULL_DATE.matcher(body);
        if (full.find()) {
            return JalaliDate.of(Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2)), Integer.parseInt(full.group(3))).toString();
        }
        Matcher shortDate = SHORT_DATE.matcher(body);
        while (shortDate.find()) {
            int month = Integer.parseInt(shortDate.group(1));
            int day = Integer.parseInt(shortDate.group(2));
            if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                return JalaliDate.of(today.year(), month, Math.min(day, JalaliDate.lengthOfMonth(today.year(), month))).toString();
            }
        }
        return today.toString();
    }

    // ---------------------------------------------------------------- reports and categories

    private static Map<String, Object> report(LlmRequest request) {
        JsonNode bundle = read(between(text(request.messages().getFirst()), "<data>", "</data>"));
        JsonNode totals = bundle == null ? null : bundle.path("totals");
        String month = bundle == null ? "" : bundle.path("month").asString("");
        String income = totals == null ? "—" : totals.path("income_toman").asString("۰");
        String expense = totals == null ? "—" : totals.path("expense_toman").asString("۰");
        String rate = totals == null ? null : totals.path("savings_rate").asString(null);
        List<Map<String, Object>> highlights = new ArrayList<>();
        highlights.add(Map.of("title", "درآمد", "detail", "درآمد این ماه " + income + " تومان بود.", "tone", "NEUTRAL"));
        highlights.add(Map.of("title", "هزینه", "detail", "هزینه‌ی این ماه " + expense + " تومان بود.", "tone", "NEUTRAL"));
        if (rate != null) {
            highlights.add(Map.of("title", "نرخ پس‌انداز", "detail", "نرخ پس‌انداز " + rate + " بود.", "tone",
                    rate.startsWith("-") ? "NEGATIVE" : "POSITIVE"));
        }
        JsonNode top = bundle == null ? null : bundle.path("expenses_by_category").path(0);
        List<Map<String, Object>> suggestions = new ArrayList<>();
        if (top != null && top.hasNonNull("name")) {
            suggestions.add(Map.of("title", "بودجه برای «" + top.path("name").asString() + "»",
                    "detail", "بیشترین هزینه در این دسته بود (" + top.path("value_toman").asString() + " تومان)؛ برایش سقف ماهانه بگذارید."));
        }
        suggestions.add(Map.of("title", "پس‌انداز خودکار", "detail", "اول هر ماه بخشی از درآمد را به حساب پس‌انداز منتقل کنید."));
        return Map.of(
                "headline", "مروری بر " + (month.contains("(") ? month.substring(month.indexOf('(') + 1, month.lastIndexOf(')')) : month),
                "summary", "در این ماه **" + income + " تومان** درآمد و **" + expense + " تومان** هزینه ثبت شده است."
                        + (rate != null ? " نرخ پس‌انداز " + rate + " بود." : ""),
                "highlights", highlights,
                "suggestions", suggestions);
    }

    private static Map<String, Object> categories(LlmRequest request) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        String context = request.messages().getFirst().parts().stream().filter(p -> p instanceof Part.Context)
                .map(p -> ((Part.Context) p).text()).collect(Collectors.joining());
        JsonNode categories = read(context.substring(context.indexOf('[')));
        JsonNode transactions = read(between(text(request.messages().getFirst()), "<transactions>", "</transactions>"));
        if (categories == null || transactions == null) {
            return Map.of("suggestions", suggestions);
        }
        for (JsonNode t : transactions) {
            String description = PersianText.normalize(t.path("description").asString(""));
            for (JsonNode c : categories) {
                String name = PersianText.normalize(c.path("name").asString(""));
                if (!name.isEmpty() && c.path("kind").asString().equals(t.path("kind").asString()) && description.contains(name)) {
                    suggestions.add(Map.of("n", t.path("n").asInt(), "category_id", c.path("id").asLong(), "confidence", "HIGH"));
                    break;
                }
            }
        }
        return Map.of("suggestions", suggestions);
    }

    // ---------------------------------------------------------------- helpers

    private static JalaliDate today(LlmRequest request) {
        for (LlmMessage m : request.messages()) {
            for (Part p : m.parts()) {
                if (p instanceof Part.Context c) {
                    Matcher matcher = TODAY.matcher(c.text());
                    if (matcher.find()) {
                        return JalaliDate.parse(matcher.group(1));
                    }
                }
            }
        }
        return JalaliDate.from(java.time.LocalDate.now());
    }

    private static String toolName(List<LlmMessage> messages, String callId) {
        for (LlmMessage m : messages) {
            for (Part p : m.parts()) {
                if (p instanceof Part.ToolCall call && call.id().equals(callId)) {
                    return call.name();
                }
            }
        }
        return "";
    }

    private static String text(LlmMessage message) {
        return message.parts().stream().filter(p -> p instanceof Part.Text).map(p -> ((Part.Text) p).text()).collect(Collectors.joining("\n"));
    }

    private static String between(String text, String start, String end) {
        int s = text.indexOf(start);
        int e = text.lastIndexOf(end);
        return s >= 0 && e > s ? text.substring(s + start.length(), e).strip() : text;
    }

    private static JsonNode read(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }
}
