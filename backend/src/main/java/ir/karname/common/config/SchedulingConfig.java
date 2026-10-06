package ir.karname.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (recurring postings, notifications, prices); off in tests, which call the jobs directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "karname.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
