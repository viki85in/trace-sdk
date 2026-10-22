package org.util.trace.core.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** One source of {@code trace.*} configuration values. Internal to the SDK. */
public interface ConfigSource {

    /** Human-readable name, shown as the "source" of an effective value. */
    String name();

    /** The raw value for {@code key}, or {@code null} if this source doesn't define it. */
    String get(String key);

    /** All keys starting with {@code prefix}, with their raw values. Used for per-operation overrides. */
    default Map<String, String> withPrefix(String prefix) {
        return Collections.emptyMap();
    }

    static ConfigSource systemProperties() {
        return new ConfigSource() {
            @Override
            public String name() {
                return "system property";
            }

            @Override
            public String get(String key) {
                return System.getProperty(key);
            }

            @Override
            public Map<String, String> withPrefix(String prefix) {
                return ConfigSources.prefixed(System.getProperties(), prefix);
            }
        };
    }

    /**
     * Environment variables: the key in upper case with {@code .} replaced by {@code _}
     * ({@code trace.level} → {@code TRACE_LEVEL}). Per-operation overrides are not read from the
     * environment because the mapping back to dotted operation names is ambiguous.
     */
    static ConfigSource environment(Map<String, String> env) {
        return new ConfigSource() {
            @Override
            public String name() {
                return "env";
            }

            @Override
            public String get(String key) {
                return env.get(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
            }
        };
    }

    static ConfigSource properties(String name, Properties properties) {
        return new ConfigSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String get(String key) {
                return properties.getProperty(key);
            }

            @Override
            public Map<String, String> withPrefix(String prefix) {
                return ConfigSources.prefixed(properties, prefix);
            }
        };
    }

    static ConfigSource map(String name, Map<String, String> values) {
        return new ConfigSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String get(String key) {
                return values.get(key);
            }

            @Override
            public Map<String, String> withPrefix(String prefix) {
                Map<String, String> out = new HashMap<>();
                values.forEach((k, v) -> {
                    if (k.startsWith(prefix)) {
                        out.put(k, v);
                    }
                });
                return out;
            }
        };
    }
}
