package org.util.trace.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

/** An OTLP/HTTP JSON collector on a random local port that keeps every span it receives. */
final class FakeCollector implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final List<JsonNode> spans = new CopyOnWriteArrayList<>();

    FakeCollector() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/traces", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                body = new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes();
            }
            for (JsonNode rs : JSON.readTree(body).path("resourceSpans")) {
                for (JsonNode ss : rs.path("scopeSpans")) {
                    ss.path("spans").forEach(spans::add);
                }
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
    }

    /** Properties that point the SDK at this collector. */
    Map<String, Object> properties(String... extra) {
        Map<String, Object> props = new HashMap<>();
        props.put("trace.service.name", "spring-test");
        props.put("trace.exporter.endpoint", endpoint());
        props.put("trace.exporter.protocol", "http/json");
        props.put("trace.batch.max.delay.ms", "50");
        for (int i = 0; i < extra.length; i += 2) {
            props.put(extra[i], extra[i + 1]);
        }
        return props;
    }

    List<JsonNode> spans() {
        return new ArrayList<>(spans);
    }

    JsonNode span(String name) {
        List<JsonNode> matches = spans.stream().filter(s -> s.path("name").asText().equals(name)).toList();
        if (matches.size() != 1) {
            throw new AssertionError("expected one span named " + name + " but got " + names());
        }
        return matches.get(0);
    }

    List<String> names() {
        return spans.stream().map(s -> s.path("name").asText()).toList();
    }

    static Map<String, JsonNode> attributes(JsonNode node) {
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode kv : node.path("attributes")) {
            JsonNode value = kv.path("value");
            out.put(kv.path("key").asText(), value.elements().next());
        }
        return out;
    }

    static String attribute(JsonNode node, String key) {
        JsonNode v = attributes(node).get(key);
        return v == null ? null : v.asText();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
