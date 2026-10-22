package org.util.trace.spring.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;
import org.util.trace.spring.TraceSdkConfiguration;

/** Spring Boot auto-configuration: adding {@code trace-sdk-spring} is all an application needs to do. */
@AutoConfiguration
@Import(TraceSdkConfiguration.class)
public class TraceSdkAutoConfiguration {
}
