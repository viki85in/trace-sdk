package org.util.trace.spring.aop;

import java.lang.System.Logger;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.util.ClassUtils;
import org.util.trace.core.TraceSite;
import org.util.trace.core.site.SiteFactory;

/**
 * Caches the {@link TraceSite} for each (bean class, invoked method). Populated when the pointcut
 * is evaluated at bean creation; the interceptor then finds the pre-built site with one map lookup.
 */
final class TraceSiteRegistry {

    private static final Logger LOG = System.getLogger("org.util.trace");
    private static final Object NONE = new Object();

    private final ClassValue<Boolean> candidates = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return computeCandidate(type);
        }
    };

    private final ClassValue<ConcurrentMap<Method, Object>> sites = new ClassValue<>() {
        @Override
        protected ConcurrentMap<Method, Object> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /** Whether {@code type} (or a superclass) declares {@code @Trace} and can be proxied. */
    boolean isCandidate(Class<?> type) {
        return candidates.get(ClassUtils.getUserClass(type));
    }

    /** The site for a call to {@code method} on an instance of {@code targetClass}, or {@code null}. */
    TraceSite site(Method method, Class<?> targetClass) {
        Class<?> userClass = ClassUtils.getUserClass(targetClass);
        ConcurrentMap<Method, Object> perClass = sites.get(userClass);
        Object site = perClass.get(method);
        if (site == null) {
            Method specific = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, userClass));
            TraceSite created = SiteFactory.create(specific);
            site = created == null ? NONE : created;
            Object raced = perClass.putIfAbsent(method, site);
            if (raced != null) {
                site = raced;
            }
        }
        return site == NONE ? null : (TraceSite) site;
    }

    private static boolean computeCandidate(Class<?> type) {
        boolean traced = false;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (SiteFactory.declaresTrace(c)) {
                traced = true;
                break;
            }
        }
        if (!traced) {
            return false;
        }
        if (Modifier.isFinal(type.getModifiers())) {
            LOG.log(Logger.Level.WARNING,
                    "trace-sdk: {0} is final, so it cannot be proxied; its @Trace methods are not traced",
                    type.getName());
            return false;
        }
        return true;
    }
}
