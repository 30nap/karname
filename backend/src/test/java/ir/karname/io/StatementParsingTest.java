package ir.karname.io;

import ir.karname.common.web.ApiException;
import ir.karname.io.StatementImportService.DateStyle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementParsingTest {

    @Test
    void readsAmountsAsBanksPrintThem() {
        assertThat(StatementImportService.signed("1,250,000")).contains(new BigDecimal("1250000"));
        assertThat(StatementImportService.signed("۱٬۲۵۰٬۰۰۰ ریال")).contains(new BigDecimal("1250000"));
        assertThat(StatementImportService.signed("(450,000)")).contains(new BigDecimal("-450000"));
        assertThat(StatementImportService.signed("450,000-")).contains(new BigDecimal("-450000"));
        assertThat(StatementImportService.signed("-12.5")).contains(new BigDecimal("-12.5"));
        assertThat(StatementImportService.signed("")).isEmpty();
        assertThat(StatementImportService.signed("مانده")).isEmpty();
    }

    @Test
    void readsJalaliAndGregorianDates() {
        assertThat(StatementImportService.date("1405/07/14", DateStyle.AUTO)).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(StatementImportService.date("۱۴۰۵-۷-۱۴ ۱۲:۳۰", DateStyle.AUTO)).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(StatementImportService.date("14050714", DateStyle.AUTO)).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(StatementImportService.date("2026-10-06", DateStyle.AUTO)).isEqualTo(LocalDate.of(2026, 10, 6));
        // 30 Esfand exists only in leap years: 1403 is one
        assertThat(StatementImportService.date("1403/12/30", DateStyle.JALALI)).isEqualTo(LocalDate.of(2025, 3, 20));
        assertThatThrownBy(() -> StatementImportService.date("1404/12/30", DateStyle.JALALI)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatementImportService.date("دیروز", DateStyle.AUTO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesCommaSemicolonAndTabSeparatedFilesInEitherEncoding() {
        String text = "تاریخ;شرح;برداشت;واریز\n1405/07/01;\"خرید; فروشگاه\";500,000;\n";
        List<List<String>> utf8 = StatementImportService.parse(("﻿" + text).getBytes(StandardCharsets.UTF_8));
        assertThat(utf8).hasSize(2);
        assertThat(utf8.get(1)).containsExactly("1405/07/01", "خرید; فروشگاه", "500,000", "");

        // older Iranian bank exports are Windows-1256, which has only the Arabic yeh (ي)
        String legacy = "تاريخ\tشرح\tبرداشت\tواريز\n1405/07/01\tخريد فروشگاه\t500,000\t\n";
        List<List<String>> arabicWindows = StatementImportService.parse(legacy.getBytes(Charset.forName("windows-1256")));
        assertThat(arabicWindows.get(0)).containsExactly("تاريخ", "شرح", "برداشت", "واريز");
        assertThat(arabicWindows.get(1)).containsExactly("1405/07/01", "خريد فروشگاه", "500,000", "");

        assertThatThrownBy(() -> StatementImportService.parse(new byte[0])).isInstanceOf(ApiException.class);
    }

    @Test
    void guardsSpreadsheetFormulas() {
        assertThat(CsvExportService.text("=HYPERLINK(\"x\")")).isEqualTo("'=HYPERLINK(\"x\")");
        assertThat(CsvExportService.text("+98912")).isEqualTo("'+98912");
        assertThat(CsvExportService.text("اسنپ")).isEqualTo("اسنپ");
    }
}
