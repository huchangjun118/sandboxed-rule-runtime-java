package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the rule runtime as singletons: one version registry, one shared
 * bounded execution pool wrapped by the facade. The facade owns the executor
 * lifecycle (destroy method closes the pool).
 */
@Configuration
@EnableConfigurationProperties(RuntimeProperties.class)
public class SandboxConfiguration {

    @Bean
    public RuleStore ruleStore() {
        return new RuleStore();
    }

    @Bean
    public RuleExecutor ruleExecutor(RuntimeProperties properties) {
        return new RuleExecutor(properties.getWorkerThreads(), properties.toLimits());
    }

    @Bean(destroyMethod = "close")
    public RuleExecutionService ruleExecutionService(RuleStore store,
                                                     RuleExecutor executor) {
        return new RuleExecutionService(store, executor);
    }
}
