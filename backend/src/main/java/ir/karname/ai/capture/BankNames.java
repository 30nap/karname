package ir.karname.ai.capture;

import ir.karname.account.Bank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The names Iranians use for their banks, as they appear in bank SMS and in conversation. */
public final class BankNames {

    /** Every bank but OTHER, in the enum's order. */
    public static final Map<Bank, String> NAMES = Collections.unmodifiableMap(names());

    private BankNames() {
    }

    /** Words that are also common Persian words carry «بانک». */
    private static Map<Bank, String> names() {
        Map<Bank, String> names = new LinkedHashMap<>();
        names.put(Bank.MELLI, "ملی");
        names.put(Bank.MELLAT, "ملت");
        names.put(Bank.SADERAT, "صادرات");
        names.put(Bank.TEJARAT, "تجارت");
        names.put(Bank.SEPAH, "سپه");
        names.put(Bank.KESHAVARZI, "کشاورزی");
        names.put(Bank.MASKAN, "مسکن");
        names.put(Bank.REFAH, "رفاه");
        names.put(Bank.POST, "پست بانک");
        names.put(Bank.TOSEE_SADERAT, "توسعه صادرات");
        names.put(Bank.TOSEE_TAAVON, "توسعه تعاون");
        names.put(Bank.SANAT_MADAN, "صنعت و معدن");
        names.put(Bank.PASARGAD, "پاسارگاد");
        names.put(Bank.SAMAN, "سامان");
        names.put(Bank.PARSIAN, "پارسیان");
        names.put(Bank.EGHTESAD_NOVIN, "اقتصاد نوین");
        names.put(Bank.KARAFARIN, "کارآفرین");
        names.put(Bank.SINA, "بانک سینا");
        names.put(Bank.SHAHR, "بانک شهر");
        names.put(Bank.DAY, "بانک دی");
        names.put(Bank.SARMAYEH, "بانک سرمایه");
        names.put(Bank.AYANDEH, "بانک آینده");
        names.put(Bank.GARDESHGARI, "گردشگری");
        names.put(Bank.IRAN_ZAMIN, "ایران زمین");
        names.put(Bank.KHAVARMIANEH, "خاورمیانه");
        names.put(Bank.MEHR_IRAN, "مهر ایران");
        names.put(Bank.RESALAT, "رسالت");
        names.put(Bank.MELAL, "بانک ملل");
        names.put(Bank.NOOR, "بانک نور");
        names.put(Bank.BLU, "بلو");
        return names;
    }
}
