package ir.karname.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
class AiConfig {

    /** Runs chat turns, which mostly wait on the provider: one virtual thread each. */
    @Bean(destroyMethod = "close")
    ExecutorService aiExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
