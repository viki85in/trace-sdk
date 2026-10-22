package org.util.trace.core.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.util.trace.core.Span;
import org.util.trace.core.Stats;
import org.util.trace.core.config.TraceConfig;
import org.util.trace.core.export.otlp.OtlpEncoder;

/**
 * OTLP/HTTP exporter using only {@code java.net.http}. Batches are gzip-compressed by default and
 * retried with bounded exponential backoff and jitter on network errors and on 429/502/503/504.
 */
public final class OtlpHttpExporter implements SpanExporter {

    private static final Logger LOG = System.getLogger("org.util.trace");
    private static final long BASE_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long MAX_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final TraceConfig config;
    private final Stats stats;
    private final OtlpEncoder encoder;
    private final HttpClient client;
    private final Duration timeout;
    private long lastErrorLogNanos;

    public OtlpHttpExporter(TraceConfig config, Stats stats) {
        this.config = config;
        this.stats = stats;
        this.encoder = OtlpEncoder.forConfig(config);
        this.timeout = Duration.ofMillis(config.timeoutMs());
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public boolean export(List<Span> spans, long deadlineNanos) {
        byte[] body;
        try {
            body = encoder.encode(spans);
            if (config.gzip()) {
                body = gzip(body);
            }
        } catch (RuntimeException | IOException e) {
            stats.recordExportError();
            logError("encoding failed: " + e);
            return false;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.endpoint())
                .timeout(timeout)
                .header("Content-Type", encoder.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (config.gzip()) {
            builder.header("Content-Encoding", "gzip");
        }
        for (Map.Entry<String, String> h : config.headers().entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
        HttpRequest request = builder.build();

        for (int attempt = 1; ; attempt++) {
            String failure;
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    return true;
                }
                failure = "HTTP " + code;
                if (!(code == 429 || code == 502 || code == 503 || code == 504)) {
                    stats.recordExportError();
                    logError(failure + " (not retryable)");
                    return false;
                }
            } catch (IOException e) {
                failure = e.toString();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stats.recordExportError();
                return false;
            }
            stats.recordExportError();
            if (attempt >= config.retryMaxAttempts()) {
                logError(failure + " after " + attempt + " attempts");
                return false;
            }
            long backoff = Math.min(MAX_BACKOFF_NANOS, BASE_BACKOFF_NANOS << Math.min(attempt - 1, 20));
            backoff = backoff / 2 + ThreadLocalRandom.current().nextLong(backoff / 2 + 1);
            if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() + backoff - deadlineNanos > 0) {
                logError(failure + "; out of time to retry");
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    /** At most one log line per 30 s; the counters carry the full picture. */
    private void logError(String message) {
        long now = System.nanoTime();
        if (lastErrorLogNanos == 0 || now - lastErrorLogNanos > TimeUnit.SECONDS.toNanos(30)) {
            lastErrorLogNanos = now;
            LOG.log(Logger.Level.WARNING, "trace-sdk: export to {0} failed: {1}", config.endpoint(), message);
        }
    }
}
