package ir.karname.ledger;

import ir.karname.ledger.CostBasisService.CostBasisView;
import ir.karname.ledger.CostBasisService.Movement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostBasisComputationTest {

    private static Movement m(String quantity, String toman) {
        return new Movement(LocalDate.of(2026, 1, 1), new BigDecimal(quantity), toman == null ? null : new BigDecimal(toman));
    }

    @Test
    void weightedAverageWithPartialSale() {
        // buy 10 g @ 8M, buy 10 g @ 9M, sell 5 g for 50M
        CostBasisView v = CostBasisService.compute(1, "GOLD18",
                List.of(m("10", "80000000"), m("10", "90000000"), m("-5", "50000000")), new BigDecimal("10000000"));
        assertThat(v.quantity()).isEqualByComparingTo("15");
        assertThat(v.averageCostToman()).isEqualByComparingTo("8500000");
        assertThat(v.costBasisToman()).isEqualByComparingTo("127500000");
        assertThat(v.realizedToman()).isEqualByComparingTo("7500000"); // 50M - 5×8.5M
        assertThat(v.marketValueToman()).isEqualByComparingTo("150000000");
        assertThat(v.unrealizedToman()).isEqualByComparingTo("22500000");
        assertThat(v.costComplete()).isTrue();
    }

    @Test
    void unknownAcquisitionCostIsFlagged() {
        CostBasisView v = CostBasisService.compute(1, "USD", List.of(m("100", null), m("100", "9000000")), null);
        assertThat(v.costComplete()).isFalse();
        assertThat(v.quantity()).isEqualByComparingTo("200");
        assertThat(v.marketValueToman()).isNull();
        assertThat(v.unrealizedToman()).isNull();
    }

    @Test
    void sellingEverythingResetsCost() {
        CostBasisView v = CostBasisService.compute(1, "BTC", List.of(m("0.5", "1000000000"), m("-0.5", "1200000000")), new BigDecimal("2500000000"));
        assertThat(v.quantity()).isEqualByComparingTo("0");
        assertThat(v.costBasisToman()).isEqualByComparingTo("0");
        assertThat(v.averageCostToman()).isNull();
        assertThat(v.realizedToman()).isEqualByComparingTo("200000000");
        assertThat(v.unrealizedToman()).isNull();
    }
}
