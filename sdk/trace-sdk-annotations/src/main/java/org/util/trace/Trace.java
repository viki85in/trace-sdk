package org.util.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public method, or every public method declared in a class, for tracing.
 *
 * <p>On a class, {@link #operation()} is a prefix: a method's operation name becomes
 * {@code <prefix>.<method_name_in_snake_case>}. On a method, {@link #operation()} is the full
 * operation name. Values set on a method override the class values; values left at their
 * defaults are inherited from the class.
 *
 * <p>Only calls that go through the container proxy are traced: the class must be a bean, and
 * calls a class makes to its own methods ({@code this.method()}) are not traced.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Trace {

    /** Full operation name on a method, or the prefix on a class. Lower case, dot-separated. */
    String operation() default "";

    /** Logical component that owns the traced code. Required on the class or the method. */
    String component() default "";

    /** Span kind. {@link Kind#INHERIT} takes the class value, else {@link Kind#INTERNAL}. */
    Kind kind() default Kind.INHERIT;

    /**
     * Minimum effective level at which this method is recorded. {@link Level#INHERIT} takes the
     * class value, else {@link Level#L0_ENTRY} for {@link Kind#ENTRY} and
     * {@link Level#L1_BOUNDARY} otherwise.
     */
    Level level() default Level.INHERIT;

    /** Whether the end of this method means the execution is finished. Read from methods only. */
    Completion completion() default Completion.NONE;

    /** Retention class attached to the span. Empty takes the class value, else configuration. */
    String retention() default "";

    /** On a class: skip JavaBean accessors ({@code getX()}, {@code isX()}, {@code setX(v)}). */
    boolean excludeAccessors() default true;
}
