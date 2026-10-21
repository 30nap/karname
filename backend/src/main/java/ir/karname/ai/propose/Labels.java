package ir.karname.ai.propose;

import ir.karname.account.AccountType;
import ir.karname.account.Bank;
import ir.karname.ai.capture.BankNames;
import ir.karname.cheque.ChequeDirection;
import ir.karname.cheque.ChequeStatus;
import ir.karname.loan.LoanMethod;
import ir.karname.recurring.Frequency;
import ir.karname.transaction.TransactionType;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Persian names on the cards, as the app's own pages show them. */
final class Labels {

    private static final Map<AccountType, String> ACCOUNT_TYPES = new EnumMap<>(Map.ofEntries(
            Map.entry(AccountType.CASH, "پول نقد"), Map.entry(AccountType.BANK, "حساب بانکی"),
            Map.entry(AccountType.EWALLET, "کیف پول الکترونیک"), Map.entry(AccountType.CURRENCY, "ارز"),
            Map.entry(AccountType.GOLD, "طلا و سکه"), Map.entry(AccountType.CRYPTO, "رمزارز"),
            Map.entry(AccountType.INVESTMENT, "بورس و صندوق"), Map.entry(AccountType.PROPERTY, "ملک"),
            Map.entry(AccountType.VEHICLE, "خودرو"), Map.entry(AccountType.RECEIVABLE, "طلب از دیگران"),
            Map.entry(AccountType.OTHER_ASSET, "سایر دارایی‌ها"), Map.entry(AccountType.LOAN, "وام"),
            Map.entry(AccountType.DEBT, "بدهی به دیگران"), Map.entry(AccountType.CREDIT, "خرید اقساطی / اعتباری")));

    private Labels() {
    }

    static String accountType(AccountType type) {
        return ACCOUNT_TYPES.get(type);
    }

    static String bank(Bank bank) {
        if (bank == null) {
            return null;
        }
        String name = BankNames.NAMES.getOrDefault(bank, "سایر");
        return name.startsWith("بانک") ? name : "بانک " + name;
    }

    /** The bank enum for the model, with the Persian names users say. */
    static String banks() {
        return Arrays.stream(Bank.values()).map(b -> b.name() + (BankNames.NAMES.containsKey(b) ? " (" + BankNames.NAMES.get(b) + ")" : ""))
                .collect(Collectors.joining(", "));
    }

    static String method(LoanMethod method) {
        return method == LoanMethod.EQUAL_PRINCIPAL ? "اقساط کاهنده" : "اقساط مساوی";
    }

    static String transactionType(TransactionType type) {
        return switch (type) {
            case INCOME -> "درآمد";
            case EXPENSE -> "هزینه";
            case TRANSFER -> "انتقال";
            default -> type.name();
        };
    }

    static String direction(ChequeDirection direction) {
        return direction == ChequeDirection.ISSUED ? "صادره" : "دریافتی";
    }

    static String chequeStatus(ChequeStatus status) {
        return switch (status) {
            case PENDING -> "در انتظار";
            case CLEARED -> "پاس‌شده";
            case BOUNCED -> "برگشتی";
            case CANCELLED -> "باطل‌شده";
        };
    }

    static String frequency(Frequency frequency, int interval) {
        String unit = switch (frequency) {
            case WEEKLY -> "هفته";
            case MONTHLY -> "ماه";
            case YEARLY -> "سال";
        };
        return interval <= 1 ? "هر " + unit : "هر " + interval + " " + unit;
    }

    private static final String[] WEEKDAYS = {"شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه"};

    /** 0 = Saturday … 6 = Friday, as recurring rules count. */
    static String weekday(int day) {
        return day >= 0 && day < 7 ? WEEKDAYS[day] : null;
    }
}
