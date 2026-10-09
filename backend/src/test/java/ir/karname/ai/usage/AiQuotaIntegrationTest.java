package ir.karname.ai.usage;

import ir.karname.ai.provider.AiTask;
import ir.karname.common.web.ApiException;
import ir.karname.support.AiTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiQuotaIntegrationTest extends AiTestSupport {

    @Autowired
    AiUsageService usage;

    @RepeatedTest(5)
    void parallelRequestsCannotAllSlipUnderTheDailyLimit() throws Exception {
        fakeProvider(true, AiTask.EXTRACT);
        usage.setDailyLimit(2);
        TestUser user = createUser("sina");
        bank(user, "ملت", "10000000");

        Callable<Integer> quickAdd = () -> mvc.perform(postAs(user, "/api/v1/ai/quick-add", Map.of("text", "ناهار ۱۸۰ تومن")))
                .andReturn().getResponse().getStatus();
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            List<Future<Integer>> results = pool.invokeAll(List.of(quickAdd, quickAdd, quickAdd, quickAdd, quickAdd, quickAdd));
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        }
        assertThat(statuses).containsOnly(200, 429);
        assertThat(statuses.stream().filter(s -> s == 200)).hasSizeLessThanOrEqualTo(2);
        assertThat(usage.quota(user.id()).used()).isLessThanOrEqualTo(2);
    }

    @Test
    void aReleasedReservationIsGivenBack() {
        usage.setDailyLimit(1);
        TestUser user = createUser("sina");
        TestUser other = createUser("sara");

        usage.checkQuota(user.id());
        assertThatThrownBy(() -> usage.checkQuota(user.id())).isInstanceOf(ApiException.class);
        usage.checkQuota(other.id());

        usage.release(user.id());
        usage.checkQuota(user.id());
    }
}
