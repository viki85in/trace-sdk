package org.util.trace.bench;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** A collector stand-in in its own JVM: accepts OTLP posts, discards them and counts requests and bytes. */
final class Sink {

    private Sink() {
    }

    static void serve(int port) throws IOException {
        AtomicLong requests = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/v1/traces", exchange -> {
            try (var in = exchange.getRequestBody()) {
                bytes.addAndGet(in.readAllBytes().length);
            }
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/stats", exchange -> {
            byte[] body = ("{\"requests\":" + requests.get() + ",\"bytes\":" + bytes.get() + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/reset", exchange -> {
            requests.set(0);
            bytes.set(0);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("sink listening on " + port);
    }
}
