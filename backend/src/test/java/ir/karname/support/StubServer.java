package ir.karname.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A local HTTP server standing in for an outside API: records requests and answers from a function. */
public final class StubServer implements AutoCloseable {

    public record Recorded(String method, String path, String query, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> String.join(",", e.getValue())).findFirst().orElse(null);
        }
    }

    public record Reply(int status, String contentType, String body) {

        public static Reply json(int status, String body) {
            return new Reply(status, "application/json", body);
        }

        public static Reply sse(String body) {
            return new Reply(200, "text/event-stream", body);
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Recorded, Reply> handler;

    private StubServer(Function<Recorded, Reply> handler) throws IOException {
        this.handler = handler;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public static StubServer start(Function<Recorded, Reply> handler) {
        try {
            return new StubServer(handler);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void respond(Function<Recorded, Reply> handler) {
        this.handler = handler;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public List<Recorded> requests(String method, String pathPrefix) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Recorded recorded = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                Map.copyOf(exchange.getRequestHeaders()), body);
        requests.add(recorded);
        Reply reply;
        try {
            reply = handler.apply(recorded);
        } catch (RuntimeException e) {
            reply = Reply.json(500, "{\"error\":{\"message\":\"stub failure: " + e.getMessage() + "\"}}");
        }
        if (reply == null) {
            reply = Reply.json(404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"not found\"}}");
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
