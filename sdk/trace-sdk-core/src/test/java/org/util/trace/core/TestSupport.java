package org.util.trace.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.util.trace.core.config.ConfigSource;
import org.util.trace.core.config.TraceConfig;
import org.util.trace.core.export.SpanExporter;
import org.util.trace.core.site.SiteFactory;

public final class TestSupport {

    private TestSupport() {
    }

    /** Config from key/value pairs only (no real system properties or environment). */
    public static TraceConfig config(String... keyValues) {
        Map<String, String> values = new HashMap<>();
        values.put("trace.service.name", "test-service");
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        return TraceConfig.load(ConfigSource.map("sys", Map.of()), ConfigSource.map("env", Map.of()),
                List.of(ConfigSource.map("test", values)));
    }

    public static TraceSite site(Class<?> type, String methodName) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                TraceSite site = SiteFactory.create(m);
                if (site == null) {
                    throw new AssertionError("no site for " + type.getSimpleName() + "#" + methodName);
                }
                return site;
            }
        }
        throw new AssertionError("no method " + methodName);
    }

    /** Collects every exported span. Thread-safe. */
    public static final class CapturingExporter implements SpanExporter {
        private final List<Span> spans = new ArrayList<>();
        private int batches;

        @Override
        public synchronized boolean export(List<Span> batch, long deadlineNanos) {
            spans.addAll(batch);
            batches++;
            return true;
        }

        public synchronized List<Span> spans() {
            return new ArrayList<>(spans);
        }

        public synchronized int batches() {
            return batches;
        }
    }
}
