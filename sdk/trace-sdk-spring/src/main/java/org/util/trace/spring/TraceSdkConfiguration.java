package org.util.trace.spring;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.util.trace.core.TraceRuntime;
import org.util.trace.core.config.TraceConfig;
import org.util.trace.spring.aop.TraceAdvisor;

/**
 * Registers the Trace SDK in a Spring application context. Spring Boot applications get this
 * automatically; plain Spring applications add {@code @Import(TraceSdkConfiguration.class)}.
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@Import(TraceAutoProxyRegistrar.class)
public class TraceSdkConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static TraceRuntime traceSdkRuntime(Environment environment) {
        return TraceRuntime.create(TraceConfig.load(List.of(new SpringEnvironmentConfigSource(environment))));
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static TraceAdvisor traceSdkAdvisor(TraceRuntime traceSdkRuntime, Environment environment) {
        return new TraceAdvisor(traceSdkRuntime, order(environment.getProperty("trace.aop.order")));
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static TraceSdkLifecycle traceSdkLifecycle(TraceRuntime traceSdkRuntime) {
        return new TraceSdkLifecycle(traceSdkRuntime);
    }

    /** {@code trace.aop.order}: {@code HIGHEST_PRECEDENCE} (default, outermost), {@code LOWEST_PRECEDENCE} or an int. */
    static int order(String value) {
        if (value == null || value.isBlank()) {
            return Ordered.HIGHEST_PRECEDENCE;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.equals("HIGHEST_PRECEDENCE")) {
            return Ordered.HIGHEST_PRECEDENCE;
        }
        if (v.equals("LOWEST_PRECEDENCE")) {
            return Ordered.LOWEST_PRECEDENCE;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            System.getLogger("org.util.trace").log(System.Logger.Level.WARNING,
                    "trace-sdk: invalid trace.aop.order ''{0}''; using HIGHEST_PRECEDENCE", value);
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
