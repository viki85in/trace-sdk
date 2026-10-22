package org.util.trace.core.site;

import java.lang.System.Logger;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.util.trace.Capture;
import org.util.trace.CaptureReturn;
import org.util.trace.Completion;
import org.util.trace.Kind;
import org.util.trace.Level;
import org.util.trace.NoTrace;
import org.util.trace.Trace;
import org.util.trace.core.CaptureKeys;
import org.util.trace.core.CaptureSpec;
import org.util.trace.core.Levels;
import org.util.trace.core.TraceSite;

/**
 * Reads the annotations on a method and its declaring class and builds a {@link TraceSite}.
 *
 * <p>This is the runtime half of the rules in spec §7.2/§7.3: an invalid site is logged and
 * skipped, never thrown, so an application that forgot the annotation processor still runs.
 * Called once per method when the bean is created, never on the hot path.
 */
public final class SiteFactory {

    private static final Logger LOG = System.getLogger("org.util.trace");

    /** Constraint C-11. */
    static final Pattern OPERATION = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9_]+)+$");

    private static final MethodType OBJECT_TO_OBJECT = MethodType.methodType(Object.class, Object.class);

    private SiteFactory() {
    }

    /** Whether {@code type} or a method it declares carries {@link Trace}. Does not look at supertypes. */
    public static boolean declaresTrace(Class<?> type) {
        if (type.isAnnotationPresent(Trace.class)) {
            return true;
        }
        for (Method m : type.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Trace.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the site for {@code method}, the most specific implementation that will run, or returns
     * {@code null} if the method isn't traced or its annotations are invalid (logged).
     */
    public static TraceSite create(Method method) {
        Class<?> declaring = method.getDeclaringClass();
        Trace onMethod = method.getAnnotation(Trace.class);
        Trace onClass = declaring.getAnnotation(Trace.class);
        if (onMethod == null && onClass == null) {
            return null;
        }
        int mods = method.getModifiers();
        if (method.isBridge() || method.isSynthetic() || Modifier.isStatic(mods) || declaring == Object.class) {
            return null;
        }
        if (onMethod == null) {
            if (!Modifier.isPublic(mods) || method.isAnnotationPresent(NoTrace.class)
                    || isObjectMethod(method) || (onClass.excludeAccessors() && isAccessor(method))) {
                return null;
            }
        } else {
            if (!Modifier.isPublic(mods)) {
                warn(method, "@Trace is only allowed on public methods");
                return null;
            }
            if (method.isAnnotationPresent(NoTrace.class)) {
                warn(method, "has both @Trace and @NoTrace");
                return null;
            }
        }
        if (Modifier.isAbstract(mods) || declaring.isInterface()) {
            warn(method, "@Trace on an abstract or interface method; annotate the concrete method");
            return null;
        }
        if (Modifier.isFinal(mods)) {
            warn(method, "is final, so the proxy cannot intercept it");
            return null;
        }

        String operation = operation(method, onMethod, onClass);
        if (operation == null) {
            warn(method, "has no operation (set @Trace(operation) on the method or a prefix on the class)");
            return null;
        }
        if (!OPERATION.matcher(operation).matches()) {
            warn(method, "operation '" + operation + "' must match " + OPERATION.pattern());
            return null;
        }
        String component = firstNonEmpty(onMethod == null ? "" : onMethod.component(),
                onClass == null ? "" : onClass.component());
        if (component == null) {
            warn(method, "has no component (set @Trace(component) on the class or the method)");
            return null;
        }
        Kind kind = onMethod != null && onMethod.kind() != Kind.INHERIT ? onMethod.kind()
                : onClass != null && onClass.kind() != Kind.INHERIT ? onClass.kind()
                : Kind.INTERNAL;
        Level level = onMethod != null && onMethod.level() != Level.INHERIT ? onMethod.level()
                : onClass != null && onClass.level() != Level.INHERIT ? onClass.level()
                : kind == Kind.ENTRY ? Level.L0_ENTRY : Level.L1_BOUNDARY;
        boolean endsExecution = onMethod != null && onMethod.completion() == Completion.ENDS_EXECUTION;
        String retention = firstNonEmpty(onMethod == null ? "" : onMethod.retention(),
                onClass == null ? "" : onClass.retention());

        List<CaptureSpec> captures = new ArrayList<>();
        Class<?>[] paramTypes = method.getParameterTypes();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < paramTypes.length; i++) {
            for (Annotation a : paramAnnotations[i]) {
                if (a instanceof Capture) {
                    Capture c = (Capture) a;
                    MethodHandle[] path = capturePath(method, "parameter " + i, c.as(), paramTypes[i], c.path());
                    if (path != null) {
                        captures.add(new CaptureSpec(c.as(), i, path));
                    }
                }
            }
        }
        CaptureSpec returnCapture = null;
        CaptureReturn cr = method.getAnnotation(CaptureReturn.class);
        if (cr != null) {
            if (method.getReturnType() == void.class) {
                warnCapture(method, "@CaptureReturn on a void method");
            } else {
                MethodHandle[] path = capturePath(method, "return value", cr.as(), method.getReturnType(), cr.path());
                if (path != null) {
                    returnCapture = CaptureSpec.forReturn(cr.as(), path);
                }
            }
        }

        return new TraceSite(operation, component, kind, Levels.of(level), endsExecution, retention,
                declaring.getName(), method.getName(), 0, captures.toArray(new CaptureSpec[0]), returnCapture);
    }

    // ---------------------------------------------------------------- naming

    private static String operation(Method method, Trace onMethod, Trace onClass) {
        if (onMethod != null && !onMethod.operation().isEmpty()) {
            return onMethod.operation();
        }
        if (onClass != null && !onClass.operation().isEmpty()) {
            return onClass.operation() + "." + snakeCase(method.getName());
        }
        return null;
    }

    /** {@code placeOrder} → {@code place_order}; {@code getHTTPStatus} → {@code get_http_status}. */
    static String snakeCase(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                boolean prevLowerOrDigit = i > 0
                        && (Character.isLowerCase(name.charAt(i - 1)) || Character.isDigit(name.charAt(i - 1)));
                boolean acronymEnd = i > 0 && Character.isUpperCase(name.charAt(i - 1))
                        && i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
                if (prevLowerOrDigit || acronymEnd) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static String firstNonEmpty(String a, String b) {
        if (!a.isEmpty()) {
            return a;
        }
        return b.isEmpty() ? null : b;
    }

    // ---------------------------------------------------------------- exclusions

    private static boolean isObjectMethod(Method m) {
        String name = m.getName();
        int n = m.getParameterCount();
        return (n == 0 && (name.equals("toString") || name.equals("hashCode")))
                || (n == 1 && name.equals("equals") && m.getParameterTypes()[0] == Object.class);
    }

    private static boolean isAccessor(Method m) {
        String name = m.getName();
        int n = m.getParameterCount();
        Class<?> ret = m.getReturnType();
        if (n == 0 && ret != void.class && name.length() > 3 && name.startsWith("get")
                && Character.isUpperCase(name.charAt(3))) {
            return true;
        }
        if (n == 0 && (ret == boolean.class || ret == Boolean.class) && name.length() > 2 && name.startsWith("is")
                && Character.isUpperCase(name.charAt(2))) {
            return true;
        }
        return n == 1 && ret == void.class && name.length() > 3 && name.startsWith("set")
                && Character.isUpperCase(name.charAt(3));
    }

    // ---------------------------------------------------------------- capture paths

    private static MethodHandle[] capturePath(Method method, String what, String key, Class<?> type, String path) {
        if (!CaptureKeys.isValid(key)) {
            warnCapture(method, what + ": capture key '" + key + "' is neither reserved nor 'app.'-prefixed");
            return null;
        }
        List<MethodHandle> handles = new ArrayList<>();
        Class<?> current = type;
        if (!path.isEmpty()) {
            for (String segment : path.split("\\.", -1)) {
                if (segment.isEmpty() || current.isPrimitive()) {
                    warnCapture(method, what + ": invalid capture path '" + path + "'");
                    return null;
                }
                Accessor accessor = accessor(current, segment);
                if (accessor == null) {
                    warnCapture(method, what + ": '" + segment + "' in path '" + path + "' does not resolve on "
                            + current.getName());
                    return null;
                }
                handles.add(accessor.handle);
                current = accessor.type;
            }
        }
        if (!isScalarType(current)) {
            warnCapture(method, what + ": capture '" + key + "' resolves to " + current.getName() + ", which is not a scalar");
            return null;
        }
        return handles.toArray(new MethodHandle[0]);
    }

    private static final class Accessor {
        final MethodHandle handle;
        final Class<?> type;

        Accessor(MethodHandle handle, Class<?> type) {
            this.handle = handle;
            this.type = type;
        }
    }

    private static Accessor accessor(Class<?> type, String segment) {
        String cap = Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
        for (String name : new String[] {segment, "get" + cap, "is" + cap}) {
            Method m = publicNoArg(type, name);
            if (m != null) {
                MethodHandle h = unreflect(m);
                if (h != null) {
                    return new Accessor(h.asType(OBJECT_TO_OBJECT), m.getReturnType());
                }
            }
        }
        try {
            Field f = type.getField(segment);
            if (!Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                return new Accessor(MethodHandles.lookup().unreflectGetter(f).asType(OBJECT_TO_OBJECT), f.getType());
            }
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
            // fall through
        }
        return null;
    }

    private static Method publicNoArg(Class<?> type, String name) {
        try {
            Method m = type.getMethod(name);
            return m.getReturnType() == void.class || Modifier.isStatic(m.getModifiers()) ? null : m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Prefer the declaration on a public interface or superclass, so JDK types such as
     * {@code List.size()} resolve without reflective access to non-public implementation classes.
     */
    private static MethodHandle unreflect(Method m) {
        try {
            return MethodHandles.publicLookup().unreflect(m);
        } catch (IllegalAccessException e) {
            try {
                m.setAccessible(true);
                return MethodHandles.lookup().unreflect(m);
            } catch (IllegalAccessException | RuntimeException e2) {
                return null;
            }
        }
    }

    private static boolean isScalarType(Class<?> t) {
        return (t.isPrimitive() && t != void.class) || Number.class.isAssignableFrom(t) || t == Boolean.class
                || t == Character.class || CharSequence.class.isAssignableFrom(t) || t.isEnum()
                || t == Object.class; // Object: checked at runtime, non-scalars are skipped
    }

    private static void warn(Method method, String message) {
        LOG.log(Logger.Level.WARNING, "trace-sdk: {0}.{1} {2}; not traced",
                method.getDeclaringClass().getName(), method.getName(), message);
    }

    private static void warnCapture(Method method, String message) {
        LOG.log(Logger.Level.WARNING, "trace-sdk: {0}.{1} {2}; capture skipped",
                method.getDeclaringClass().getName(), method.getName(), message);
    }
}
