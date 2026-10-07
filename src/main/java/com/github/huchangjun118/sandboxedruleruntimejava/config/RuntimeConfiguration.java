package com.github.huchangjun118.sandboxedruleruntimejava.config;

import com.github.huchangjun118.sandboxedruleruntimejava.runtime.DefaultRuleRuntime;
import com.github.huchangjun118.sandboxedruleruntimejava.runtime.RuleRuntime;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.GraalSandboxEngine;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.SandboxPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the sandbox policy, the Graal engine and the rule runtime as Spring beans.
 */
@Configuration
@EnableConfigurationProperties(RuntimeProperties.class)
public class RuntimeConfiguration {

    @Bean
    public SandboxPolicy sandboxPolicy() {
        return new SandboxPolicy();
    }

    @Bean
    public GraalSandboxEngine graalSandboxEngine(SandboxPolicy policy) {
        return new GraalSandboxEngine(policy);
    }

    @Bean
    public RuleRuntime ruleRuntime(RuntimeProperties properties,
                                   GraalSandboxEngine graalSandboxEngine) {
        DefaultRuleRuntime runtime = new DefaultRuleRuntime(properties, graalSandboxEngine);
        // Release Graal engines and pools when the context stops.
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::shutdown, "rule-runtime-shutdown"));
        return runtime;
    }
}
