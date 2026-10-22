package org.util.trace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Records a scalar from the return value of a traced method. Same key rules as {@link Capture}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CaptureReturn {

    /** Attribute key. */
    String as();

    /** Dot-separated property path into the return value; empty records the value itself. */
    String path() default "";
}
