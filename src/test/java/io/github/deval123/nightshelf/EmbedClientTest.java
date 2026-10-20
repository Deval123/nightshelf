package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Talks to a fake Ollama on a local port; a real Ollama is never contacted. */
class EmbedClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Function<Integer, Object[]> script = n -> new Object[] {200, "{\"embeddings\": [[0.5, 0.25]]}"};
    private volatile long delayMillis = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            int n = calls.getAndIncrement();
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                // shutting down
            }
            Object[] reply = script.apply(n);
            byte[] out = ((String) reply[1]).getBytes(StandardCharsets.UTF_8);
            try {
                if (reply.length > 2) {
                    exchange.getResponseHeaders().add("Location", (String) reply[2]);
                }
                exchange.sendResponseHeaders((Integer) reply[0], out.length);
                exchange.getResponseBody().write(out);
            } catch (IOException ignored) {
                // client gave up
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ((ExecutorService) server.getExecutor()).shutdownNow();
    }

    private EmbedClient client(Integer dim, Duration requestTimeout) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new EmbedClient(base, "embed-model", dim, Duration.ofSeconds(2), requestTimeout, Duration.ofMillis(5));
    }

    private EmbedClient client() {
        return client(null, Duration.ofSeconds(5));
    }

    @Test
    void sendsOneRequestWithTheModelAndText() throws Exception {
        float[] v = client().embed("rainy city");

        assertArrayEquals(new float[] {0.5f, 0.25f}, v);
        assertEquals(List.of("POST /api/embed"), paths);
        JsonNode req = MAPPER.readTree(bodies.get(0));
        assertEquals("embed-model", req.get("model").asText());
        assertEquals("rainy city", req.get("input").asText());
    }

    @Test
    void rejectsAnythingButExactlyOneUsableVector() {
        for (String bad : List.of(
                "{\"embeddings\": []}",
                "{\"embeddings\": [[1.0], [2.0]]}",
                "{\"embeddings\": [[]]}",
                "{\"embeddings\": [\"x\"]}",
                "{\"embeddings\": [[1.0, \"a\"]]}",
                "{\"embeddings\": [[1.0, null]]}",
                "{\"embeddings\": [[1e60]]}",
                "{\"embeddings\": [[-1e60]]}",
                "{\"other\": 1}",
                "[]")) {
            script = n -> new Object[] {200, bad};
            assertThrows(EmbedException.class, () -> client().embed("t"), bad);
        }
    }

    @Test
    void invalidVectorsAreNotRetried() {
        script = n -> new Object[] {200, "{\"embeddings\": [[1e60]]}"};

        assertThrows(EmbedException.class, () -> client().embed("t"));

        assertEquals(1, calls.get());
    }

    @Test
    void dimensionMustStayTheSame() throws Exception {
        EmbedClient c = client();
        c.embed("first");
        script = n -> new Object[] {200, "{\"embeddings\": [[1.0, 2.0, 3.0]]}"};

        EmbedException e = assertThrows(EmbedException.class, () -> c.embed("second"));

        assertTrue(e.getMessage().contains("3 dimensions"), e.getMessage());
        assertThrows(EmbedException.class, () -> client(5, Duration.ofSeconds(5)).embed("x"));
    }

    @Test
    void malformedJsonIsRetriedThenReported() {
        script = n -> new Object[] {200, "{nope"};

        EmbedException e = assertThrows(EmbedException.class, () -> client().embed("t"));

        assertTrue(e.getMessage().contains("3 attempts"), e.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void retrySucceedsAfterTransientFailure() throws Exception {
        script = n -> n < 2 ? new Object[] {503, "busy"} : new Object[] {200, "{\"embeddings\": [[1.0]]}"};

        assertArrayEquals(new float[] {1f}, client().embed("t"));
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryClientErrorsAndSanitizesTheMessage() {
        script = n -> new Object[] {404, "model \u001b[31mnot found"};

        EmbedException e = assertThrows(EmbedException.class, () -> client().embed("t"));

        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
        assertTrue(!e.getMessage().contains("\u001b"));
        assertEquals(1, calls.get());
    }

    @Test
    void doesNotFollowRedirects() {
        script = n -> new Object[] {302, "", "/elsewhere"};

        EmbedException e = assertThrows(EmbedException.class, () -> client().embed("t"));

        assertTrue(e.getMessage().contains("HTTP 302"), e.getMessage());
        assertEquals(1, calls.get());
        assertEquals(List.of("POST /api/embed"), paths);
    }

    @Test
    void capsTheResponseSize() {
        String huge = "{\"embeddings\": [[" + "1.0,".repeat(400_000) + "1.0]]}";
        script = n -> new Object[] {200, huge};

        EmbedException e = assertThrows(EmbedException.class, () -> client().embed("t"));

        assertTrue(e.getMessage().contains("too large"), e.getMessage());
    }

    @Test
    void timesOutAndGivesUpAfterBoundedRetries() {
        delayMillis = 800;

        EmbedException e = assertThrows(EmbedException.class, () -> client(null, Duration.ofMillis(150)).embed("t"));

        assertTrue(e.getMessage().contains("3 attempts"), e.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void onlyHttpAndHttpsUrlsAreAccepted() {
        for (String url : List.of("file:///etc/passwd", "ftp://h", "h:11434")) {
            assertThrows(IllegalArgumentException.class, () -> new EmbedClient(URI.create(url), "m", null), url);
        }
    }
}
