package org.util.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records a scalar argument of a traced method as a span attribute.
 *
 * <p>Only scalars are recorded: numbers, booleans, enums, characters and short strings. Use
 * {@link #path()} to reach a scalar inside the argument, e.g. {@code "items.size"}.
 *
 * <p>Reserved keys: {@code tenant.id}, {@code org.id}, {@code execution.id}, {@code traceparent},
 * {@code records.count}, {@code records.failed}, {@code batch.size}, {@code retry.attempt}.
 * Any other key must start with {@code app.}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Capture {

    /** Attribute key. */
    String as();

    /** Dot-separated property path into the argument; empty records the argument itself. */
    String path() default "";
}
