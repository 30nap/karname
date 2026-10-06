package ir.karname.common.persian;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersianTextTest {

    @Test
    void normalizesDigits() {
        assertThat(PersianText.normalizeDigits("۱۲۳۴۵۶۷۸۹۰")).isEqualTo("1234567890");
        assertThat(PersianText.normalizeDigits("٠١٢٣٤٥٦٧٨٩")).isEqualTo("0123456789");
        assertThat(PersianText.normalizeDigits("کارت ۶۰۳۷")).isEqualTo("کارت 6037");
    }

    @Test
    void convertsToPersianDigits() {
        assertThat(PersianText.toPersianDigits("1405/07/14")).isEqualTo("۱۴۰۵/۰۷/۱۴");
    }

    @Test
    void unifiesArabicLetterForms() {
        // Arabic Yeh and Kaf, as typed on Arabic keyboards and in some bank SMS messages
        String arabic = "ميوه و سبزي كرج";
        assertThat(PersianText.normalize(arabic)).isEqualTo("میوه و سبزی کرج");
    }

    @Test
    void removesTatweelAndDiacritics() {
        assertThat(PersianText.normalize("ســلام")).isEqualTo("سلام");
        assertThat(PersianText.normalize("كَتاب")).isEqualTo("کتاب");
    }

    @Test
    void buildsSearchForm() {
        assertThat(PersianText.normalizeForSearch("  می‌خواهم   SNAPP ")).isEqualTo("می خواهم snapp");
        assertThat(PersianText.normalizeForSearch(null)).isEmpty();
    }

    @Test
    void cleansBlankToNull() {
        assertThat(PersianText.clean("   ")).isNull();
        assertThat(PersianText.clean("  خرید   نان ")).isEqualTo("خرید نان");
    }
}
