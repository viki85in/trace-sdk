package org.util.trace.spring.aop;

import java.lang.reflect.Method;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.util.trace.core.TraceRuntime;

/**
 * The single advisor the SDK registers (spec §9). Its pointcut matches public methods on beans
 * whose class or methods carry {@code @Trace}; it is evaluated once per method when each bean is
 * created, which also builds and caches the {@code TraceSite}.
 */
public final class TraceAdvisor extends AbstractPointcutAdvisor {

    private static final long serialVersionUID = 1L;

    /** Used when the runtime is inactive (no service name): never proxy anything. */
    private static final Pointcut NOTHING = new Pointcut() {
        @Override
        public ClassFilter getClassFilter() {
            return type -> false;
        }

        @Override
        public MethodMatcher getMethodMatcher() {
            return MethodMatcher.TRUE;
        }
    };

    private final transient Pointcut pointcut;
    private final transient Advice advice;

    public TraceAdvisor(TraceRuntime runtime, int order) {
        setOrder(order);
        if (runtime.isActive()) {
            TraceSiteRegistry registry = new TraceSiteRegistry();
            this.pointcut = new TracePointcut(registry);
            this.advice = new TraceInterceptor(runtime.config(), runtime.tracer(), runtime.stats(), registry);
        } else {
            this.pointcut = NOTHING;
            this.advice = (MethodInterceptor) invocation -> invocation.proceed();
        }
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return advice;
    }

    private static final class TracePointcut extends StaticMethodMatcherPointcut {
        private final TraceSiteRegistry registry;

        TracePointcut(TraceSiteRegistry registry) {
            this.registry = registry;
            setClassFilter(registry::isCandidate);
        }

        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            return targetClass != null && registry.site(method, targetClass) != null;
        }
    }
}
