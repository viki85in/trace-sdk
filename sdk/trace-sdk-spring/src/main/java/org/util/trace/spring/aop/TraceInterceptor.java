package org.util.trace.spring.aop;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.util.trace.core.Span;
import org.util.trace.core.Stats;
import org.util.trace.core.TraceSite;
import org.util.trace.core.Tracer;
import org.util.trace.core.config.TraceConfig;

/**
 * The around advice (D-1, Option A): start a span, proceed, record duration and status, end the
 * span. Any SDK failure is counted and swallowed; the application's call always proceeds and its
 * result or exception passes through unchanged (C-1).
 */
final class TraceInterceptor implements MethodInterceptor {

    private final TraceConfig config;
    private final Tracer tracer;
    private final Stats stats;
    private final TraceSiteRegistry registry;

    TraceInterceptor(TraceConfig config, Tracer tracer, Stats stats, TraceSiteRegistry registry) {
        this.config = config;
        this.tracer = tracer;
        this.stats = stats;
        this.registry = registry;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        if (!config.enabled()) {
            return invocation.proceed();
        }
        Span span = null;
        try {
            Object target = invocation.getThis();
            Class<?> targetClass = target != null ? target.getClass() : invocation.getMethod().getDeclaringClass();
            TraceSite site = registry.site(invocation.getMethod(), targetClass);
            if (site != null) {
                span = tracer.begin(site, invocation.getArguments());
            }
        } catch (Throwable t) {
            stats.recordInternalError();
        }
        if (span == null) {
            return invocation.proceed();
        }
        Object result = null;
        Throwable error = null;
        try {
            result = invocation.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                tracer.end(span, result, error);
            } catch (Throwable t) {
                stats.recordInternalError();
            }
        }
    }
}
