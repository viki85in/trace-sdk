package org.util.trace.core.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.util.trace.core.Stats;
import org.util.trace.core.TestSupport;
import org.util.trace.core.config.TraceConfig;

class OtlpHttpExporterTest {

    private HttpServer server;
    private final ConcurrentLinkedQueue<Integer> responses = new ConcurrentLinkedQueue<>();
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    private final Stats stats = new Stats();

    @BeforeEach
    void startCollector() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            requests.add(headers);
            bodies.add(exchange.getRequestBody().readAllBytes());
            Integer code = responses.poll();
            exchange.sendResponseHeaders(code == null ? 200 : code, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopCollector() {
        server.stop(0);
    }

    private TraceConfig config(String... extra) {
        String[] kv = new String[extra.length + 2];
        kv[0] = "trace.exporter.endpoint";
        kv[1] = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
        System.arraycopy(extra, 0, kv, 2, extra.length);
        return TestSupport.config(kv);
    }

    @Test
    void sendsGzippedProtobufWithConfiguredHeaders() throws Exception {
        OtlpHttpExporter exporter = new OtlpHttpExporter(config("trace.exporter.headers", "Authorization=Bearer t0k"), stats);
        assertThat(exporter.export(List.of(), Long.MAX_VALUE)).isTrue();

        Map<String, String> h = requests.get(0);
        assertThat(h.get("content-type")).isEqualTo("application/x-protobuf");
        assertThat(h.get("content-encoding")).isEqualTo("gzip");
        assertThat(h.get("authorization")).isEqualTo("Bearer t0k");
        byte[] plain = new GZIPInputStream(new ByteArrayInputStream(bodies.get(0))).readAllBytes();
        assertThat(plain.length).isGreaterThan(0);
    }

    @Test
    void jsonWithoutCompression() {
        OtlpHttpExporter exporter = new OtlpHttpExporter(
                config("trace.exporter.protocol", "http/json", "trace.exporter.compression", "none"), stats);
        assertThat(exporter.export(List.of(), Long.MAX_VALUE)).isTrue();
        assertThat(requests.get(0).get("content-type")).isEqualTo("application/json");
        assertThat(requests.get(0)).doesNotContainKey("content-encoding");
        assertThat(new String(bodies.get(0))).startsWith("{\"resourceSpans\":");
    }

    @Test
    void retriesRetryableStatusesThenSucceeds() {
        responses.add(503);
        responses.add(429);
        OtlpHttpExporter exporter = new OtlpHttpExporter(config(), stats);
        assertThat(exporter.export(List.of(), Long.MAX_VALUE)).isTrue();
        assertThat(requests).hasSize(3);
        assertThat(stats.exportErrors()).isEqualTo(2);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        for (int i = 0; i < 10; i++) {
            responses.add(503);
        }
        OtlpHttpExporter exporter = new OtlpHttpExporter(config("trace.exporter.retry.max.attempts", "3"), stats);
        assertThat(exporter.export(List.of(), Long.MAX_VALUE)).isFalse();
        assertThat(requests).hasSize(3);
    }

    @Test
    void doesNotRetryClientErrors() {
        responses.add(400);
        OtlpHttpExporter exporter = new OtlpHttpExporter(config(), stats);
        assertThat(exporter.export(List.of(), Long.MAX_VALUE)).isFalse();
        assertThat(requests).hasSize(1);
    }

    @Test
    void stopsRetryingAtTheDeadline() {
        for (int i = 0; i < 10; i++) {
            responses.add(503);
        }
        OtlpHttpExporter exporter = new OtlpHttpExporter(config(), stats);
        assertThat(exporter.export(List.of(), System.nanoTime())).isFalse();
        assertThat(requests).hasSize(1);
    }

    @Test
    void unreachableCollectorFailsWithoutThrowing() {
        int port = server.getAddress().getPort();
        server.stop(0);
        OtlpHttpExporter exporter = new OtlpHttpExporter(TestSupport.config(
                "trace.exporter.endpoint", "http://127.0.0.1:" + port + "/v1/traces",
                "trace.exporter.retry.max.attempts", "2", "trace.exporter.timeout.ms", "500"), stats);
        assertThat(exporter.export(List.of(), Long.MAX_VALUE)).isFalse();
        assertThat(stats.exportErrors()).isEqualTo(2);
    }
}
