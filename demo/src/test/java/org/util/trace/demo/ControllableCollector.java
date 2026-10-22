package org.util.trace.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * A stand-in for Jaeger that tests can stop, restart on the same port, and slow down.
 * It speaks OTLP/HTTP JSON and keeps every span it receives.
 */
final class ControllableCollector implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int port;
    private final List<JsonNode> spans = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile long responseDelayMillis;
    private volatile int status = 200;
    private HttpServer server;

    /** Reserves a free port. The collector is stopped until {@link #start()} is called. */
    ControllableCollector() {
        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    synchronized ControllableCollector start() {
        if (server != null) {
            return this;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/traces", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                body = new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes();
            }
            requests.incrementAndGet();
            if (status / 100 == 2) { // a collector that rejects a request stores nothing from it
                for (JsonNode rs : JSON.readTree(body).path("resourceSpans")) {
                    for (JsonNode ss : rs.path("scopeSpans")) {
                        ss.path("spans").forEach(spans::add);
                    }
                }
            }
            if (responseDelayMillis > 0) {
                try {
                    Thread.sleep(responseDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return this;
    }

    synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    void respondAfter(long millis) {
        this.responseDelayMillis = millis;
    }

    /** The HTTP status returned to every request, e.g. 507 for "storage full". */
    void respondWith(int status) {
        this.status = status;
    }

    int requests() {
        return requests.get();
    }

    String endpoint() {
        return "http://127.0.0.1:" + port + "/v1/traces";
    }

    List<JsonNode> spans() {
        return new ArrayList<>(spans);
    }

    static String attribute(JsonNode span, String key) {
        for (JsonNode kv : span.path("attributes")) {
            if (kv.path("key").asText().equals(key)) {
                return kv.path("value").elements().next().asText();
            }
        }
        return null;
    }

    @Override
    public void close() {
        stop();
    }
}
