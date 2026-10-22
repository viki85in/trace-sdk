package org.util.trace.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.System.Logger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.util.trace.core.Levels;

/**
 * Effective SDK configuration (spec §8).
 *
 * <p>Sources, highest precedence first: runtime overrides, system properties, environment
 * variables, application sources (e.g. the Spring {@code Environment}), the config file, built-in
 * defaults. Invalid values are logged and ignored; configuration never fails application startup.
 *
 * <p>Dynamic keys are held in volatile fields and can be changed at runtime with
 * {@link #update(String, String)}.
 */
public final class TraceConfig {

    static final Logger LOG = System.getLogger("org.util.trace");

    public static final String DEFAULT_ENDPOINT = "http://localhost:4318/v1/traces";

    /** Wire encoding of OTLP/HTTP requests. */
    public enum Protocol {
        HTTP_PROTOBUF("http/protobuf"),
        HTTP_JSON("http/json");

        private final String key;

        Protocol(String key) {
            this.key = key;
        }

        static Protocol parse(String value) {
            for (Protocol p : values()) {
                if (p.key.equals(value)) {
                    return p;
                }
            }
            throw new IllegalArgumentException("expected http/protobuf or http/json");
        }
    }

    private final List<ConfigSource> sources;
    /** Backing map of the highest-precedence "runtime override" source. */
    private final Map<String, String> runtimeOverrides;

    // Static keys.
    private final String serviceName;
    private final String serviceVersion;
    private final String environment;
    private final URI endpoint;
    private final Protocol protocol;
    private final boolean gzip;
    private final Map<String, String> headers;
    private final int timeoutMs;
    private final int retryMaxAttempts;
    private final int bufferMaxSpans;
    private final int batchMaxSpans;
    private final int batchMaxDelayMs;

    // Dynamic keys (FR-9).
    private volatile boolean enabled;
    private volatile int level;
    private volatile Map<String, Integer> levelOverrides;
    private volatile String retentionClass;
    private volatile boolean captureExceptionMessage;
    private volatile int stacktraceMaxFrames;

    private TraceConfig(List<ConfigSource> sources, Map<String, String> runtimeOverrides) {
        this.sources = sources;
        this.runtimeOverrides = runtimeOverrides;

        String name = string("trace.service.name", null);
        if (name == null || name.isBlank()) {
            name = string("spring.application.name", null);
        }
        this.serviceName = name == null || name.isBlank() ? null : name.trim();
        this.serviceVersion = string("trace.service.version", null);
        this.environment = string("trace.environment", null);
        this.endpoint = resolve("trace.exporter.endpoint", TraceConfig::parseEndpoint, URI.create(DEFAULT_ENDPOINT));
        this.protocol = resolve("trace.exporter.protocol", Protocol::parse, Protocol.HTTP_PROTOBUF);
        this.gzip = resolve("trace.exporter.compression", TraceConfig::parseCompression, true);
        this.headers = resolve("trace.exporter.headers", TraceConfig::parseHeaders, Collections.emptyMap());
        this.timeoutMs = resolve("trace.exporter.timeout.ms", positiveInt(), 10_000);
        this.retryMaxAttempts = resolve("trace.exporter.retry.max.attempts", positiveInt(), 5);
        this.bufferMaxSpans = resolve("trace.buffer.max.spans", positiveInt(), 32_768);
        this.batchMaxSpans = resolve("trace.batch.max.spans", positiveInt(), 512);
        this.batchMaxDelayMs = resolve("trace.batch.max.delay.ms", positiveInt(), 1_000);

        loadDynamic();
    }

    /** Loads configuration from the standard sources plus {@code applicationSources}. */
    public static TraceConfig load(List<ConfigSource> applicationSources) {
        return load(ConfigSource.systemProperties(), ConfigSource.environment(System.getenv()), applicationSources);
    }

    /** Loads configuration with explicit system-property and environment sources (for tests). */
    public static TraceConfig load(ConfigSource systemProperties, ConfigSource environment,
                                   List<ConfigSource> applicationSources) {
        Map<String, String> runtime = new ConcurrentHashMap<>();
        List<ConfigSource> sources = new ArrayList<>();
        sources.add(ConfigSource.map("runtime override", runtime));
        sources.add(systemProperties);
        sources.add(environment);
        sources.addAll(applicationSources);
        ConfigSource file = loadFile(sources);
        if (file != null) {
            sources.add(file);
        }
        return new TraceConfig(Collections.unmodifiableList(sources), runtime);
    }

    // ---------------------------------------------------------------- accessors

    /** {@code null} if neither {@code trace.service.name} nor {@code spring.application.name} is set. */
    public String serviceName() {
        return serviceName;
    }

    public String serviceVersion() {
        return serviceVersion;
    }

    public String environment() {
        return environment;
    }

    public URI endpoint() {
        return endpoint;
    }

    public Protocol protocol() {
        return protocol;
    }

    public boolean gzip() {
        return gzip;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public int retryMaxAttempts() {
        return retryMaxAttempts;
    }

    public int bufferMaxSpans() {
        return bufferMaxSpans;
    }

    public int batchMaxSpans() {
        return batchMaxSpans;
    }

    public int batchMaxDelayMs() {
        return batchMaxDelayMs;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Global effective level as an int ({@link Levels}). */
    public int level() {
        return level;
    }

    /** Per-operation level override, or {@link Levels#OFF} if there is none. */
    public int levelOverride(String operation) {
        Map<String, Integer> overrides = levelOverrides;
        if (overrides.isEmpty()) {
            return Levels.OFF;
        }
        Integer value = overrides.get(operation);
        return value == null ? Levels.OFF : value;
    }

    public String retentionClass() {
        return retentionClass;
    }

    public boolean captureExceptionMessage() {
        return captureExceptionMessage;
    }

    public int stacktraceMaxFrames() {
        return stacktraceMaxFrames;
    }

    // ---------------------------------------------------------------- runtime changes

    /**
     * Applies a runtime override to a dynamic key. Returns {@code false}, leaving the current value
     * in effect, if the key isn't dynamic or the value is invalid.
     */
    public synchronized boolean update(String key, String value) {
        try {
            if (key.startsWith("trace.level.")) {
                Levels.parse(value);
            } else {
                switch (key) {
                    case "trace.enabled":
                    case "trace.capture.exception.message":
                        parseBoolean(value);
                        break;
                    case "trace.level":
                        Levels.parse(value);
                        break;
                    case "trace.retention.class":
                        nonBlank(value);
                        break;
                    case "trace.stacktrace.max.frames":
                        nonNegativeInt().apply(value);
                        break;
                    default:
                        LOG.log(Logger.Level.WARNING, "trace-sdk: ''{0}'' is not a dynamic key; ignored", key);
                        return false;
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "trace-sdk: invalid value ''{0}'' for {1} ({2}); ignored",
                    value, key, e.getMessage());
            return false;
        }
        runtimeOverrides.put(key, value);
        loadDynamic();
        return true;
    }

    private void loadDynamic() {
        this.enabled = resolve("trace.enabled", TraceConfig::parseBoolean, true);
        this.level = resolve("trace.level", Levels::parse, Levels.L1);
        this.levelOverrides = loadLevelOverrides();
        this.retentionClass = resolve("trace.retention.class", TraceConfig::nonBlank, "standard-30d");
        this.captureExceptionMessage = resolve("trace.capture.exception.message", TraceConfig::parseBoolean, false);
        this.stacktraceMaxFrames = resolve("trace.stacktrace.max.frames", nonNegativeInt(), 20);
    }

    // ---------------------------------------------------------------- resolution

    private String string(String key, String defaultValue) {
        for (ConfigSource source : sources) {
            String raw = source.get(key);
            if (raw != null) {
                return raw.trim();
            }
        }
        return defaultValue;
    }

    private <T> T resolve(String key, Function<String, T> parser, T defaultValue) {
        for (ConfigSource source : sources) {
            String raw = source.get(key);
            if (raw == null) {
                continue;
            }
            try {
                return parser.apply(raw.trim());
            } catch (RuntimeException e) {
                LOG.log(Logger.Level.WARNING, "trace-sdk: invalid value ''{0}'' for {1} from {2} ({3}); ignored",
                        raw, key, source.name(), e.getMessage());
            }
        }
        return defaultValue;
    }

    private Map<String, Integer> loadLevelOverrides() {
        String prefix = "trace.level.";
        Map<String, Integer> out = new HashMap<>();
        // Lowest precedence first, so higher sources overwrite.
        for (int i = sources.size() - 1; i >= 0; i--) {
            ConfigSource source = sources.get(i);
            for (Map.Entry<String, String> e : source.withPrefix(prefix).entrySet()) {
                String operation = e.getKey().substring(prefix.length());
                if (operation.isEmpty()) {
                    continue;
                }
                try {
                    out.put(operation, Levels.parse(e.getValue().trim()));
                } catch (RuntimeException ex) {
                    LOG.log(Logger.Level.WARNING, "trace-sdk: invalid value ''{0}'' for {1} from {2}; ignored",
                            e.getValue(), e.getKey(), source.name());
                }
            }
        }
        return out.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(out);
    }

    private static ConfigSource loadFile(List<ConfigSource> higher) {
        String location = null;
        for (ConfigSource source : higher) {
            location = source.get("trace.config.file");
            if (location != null) {
                break;
            }
        }
        if (location == null) {
            location = "trace-sdk.properties";
        }
        Properties properties = new Properties();
        Path path = Path.of(location);
        try {
            if (Files.isRegularFile(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                return ConfigSource.properties("file " + path, properties);
            }
            ClassLoader loader = TraceConfig.class.getClassLoader();
            try (InputStream in = loader == null ? null : loader.getResourceAsStream(location)) {
                if (in != null) {
                    properties.load(in);
                    return ConfigSource.properties("classpath " + location, properties);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "trace-sdk: cannot read config file {0} ({1}); ignored", location, e.toString());
        }
        return null;
    }

    // ---------------------------------------------------------------- parsers

    static boolean parseBoolean(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        if (v.equals("true")) {
            return true;
        }
        if (v.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException("expected true or false");
    }

    static String nonBlank(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("must not be blank");
        }
        return value;
    }

    static Function<String, Integer> positiveInt() {
        return v -> {
            int n = Integer.parseInt(v);
            if (n <= 0) {
                throw new IllegalArgumentException("must be > 0");
            }
            return n;
        };
    }

    static Function<String, Integer> nonNegativeInt() {
        return v -> {
            int n = Integer.parseInt(v);
            if (n < 0) {
                throw new IllegalArgumentException("must be >= 0");
            }
            return n;
        };
    }

    static URI parseEndpoint(String value) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("expected an http or https URL");
        }
        return uri;
    }

    static boolean parseCompression(String value) {
        if (value.equals("gzip")) {
            return true;
        }
        if (value.equals("none")) {
            return false;
        }
        throw new IllegalArgumentException("expected gzip or none");
    }

    static Map<String, String> parseHeaders(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : value.split(",")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("expected key=value pairs separated by commas");
            }
            out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return Collections.unmodifiableMap(out);
    }
}
