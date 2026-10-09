package ir.karname.ai.capture;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class NumberEndingsTest {

    @Test
    void findsMaskedAndLongNumbersButNotAmounts() {
        assertThat(CaptureService.numberEndings("برداشت از حساب ***4321 مبلغ 1,250,000")).containsExactly("4321");
        assertThat(CaptureService.numberEndings("کارت 6219-86**-****-5678")).containsExactly("5678");
        assertThat(CaptureService.numberEndings("کارت xxxx1234 و حساب 0123456789")).containsExactly("1234", "6789");
        assertThat(CaptureService.numberEndings("مانده:12,345,678 تاریخ 1405/07/10")).isEmpty();
    }

    @Test
    void longRunsOfMaskCharactersStayFast() {
        String hostile = "*".repeat(10_000) + ".-".repeat(5_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThat(CaptureService.numberEndings(hostile)).isEmpty());
        String digits = "1*" + "1".repeat(19_990) + ",";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> CaptureService.numberEndings(digits));
    }
}
