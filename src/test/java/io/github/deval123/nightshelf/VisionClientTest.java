package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Talks to a fake Ollama on a local port; a real Ollama is never contacted. */
class VisionClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GOOD = """
            {"description": "A street at night", "subjects": ["car", "lamp"],
             "setting": "city", "time_of_day": "night", "mood": "calm"}""";

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    /** Decides the reply for the n-th call (0-based): {status, body}. */
    private volatile Function<Integer, Object[]> script = n -> new Object[] {200, chat(GOOD)};
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
                // test is shutting down
            }
            Object[] reply = script.apply(n);
            byte[] out = ((String) reply[1]).getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders((Integer) reply[0], out.length);
                exchange.getResponseBody().write(out);
            } catch (IOException ignored) {
                // client gave up (timeout test)
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private VisionClient client(Duration requestTimeout) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new VisionClient(base, "test-model", Duration.ofSeconds(2), requestTimeout, Duration.ofMillis(5));
    }

    private VisionClient client() {
        return client(Duration.ofSeconds(5));
    }

    private List<Path> frames(int n) throws IOException {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Files.write(dir.resolve("f" + i + ".jpg"), new byte[] {(byte) i, 1, 2}));
        }
        return out;
    }

    /** Wraps model output the way Ollama's /api/chat does. */
    private static String chat(String content) {
        return MAPPER.createObjectNode().set("message",
                MAPPER.createObjectNode().put("role", "assistant").put("content", content)).toString();
    }

    @Test
    void sendsOneNonStreamingRequestWithAllFramesAndTheSchema() throws Exception {
        Description d = client().describe(frames(3));

        assertEquals(List.of("POST /api/chat"), paths);
        JsonNode req = MAPPER.readTree(bodies.get(0));
        assertEquals("test-model", req.get("model").asText());
        assertFalse(req.get("stream").asBoolean());
        assertEquals(0, req.get("options").get("temperature").asInt());
        assertEquals(1, req.get("messages").size());
        assertEquals("user", req.get("messages").get(0).get("role").asText());
        assertEquals(3, req.get("messages").get(0).get("images").size());
        assertEquals(400, req.get("format").get("properties").get("description").get("maxLength").asInt());
        assertEquals(8, req.get("format").get("properties").get("subjects").get("maxItems").asInt());
        assertEquals(5, req.get("format").get("properties").get("time_of_day").get("enum").size());

        assertEquals("A street at night", d.description());
        assertEquals(List.of("car", "lamp"), d.subjects());
        assertEquals("night", d.timeOfDay());
    }

    @Test
    void rejectsMalformedJsonAfterBoundedRetries() throws Exception {
        script = n -> new Object[] {200, chat("{not json")};

        DescribeException e = assertThrows(DescribeException.class, () -> client().describe(frames(1)));

        assertTrue(e.getMessage().contains("malformed"), e.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void rejectsJsonThatBreaksTheSchema() throws Exception {
        for (String bad : List.of(
                "[]",
                "{\"description\": \"x\", \"subjects\": \"car\", \"setting\": \"s\","
                        + " \"time_of_day\": \"day\", \"mood\": \"m\"}",
                "{\"description\": \"x\", \"subjects\": [], \"setting\": \"s\","
                        + " \"time_of_day\": \"midnight\", \"mood\": \"m\"}",
                "{\"description\": \"\", \"subjects\": [], \"setting\": \"s\","
                        + " \"time_of_day\": \"day\", \"mood\": \"m\"}",
                "{\"subjects\": []}")) {
            assertThrows(DescribeException.class, () -> VisionClient.parse(chat(bad)), bad);
        }
    }

    @Test
    void capsOverLongText() throws Exception {
        String longText = "a".repeat(5000);
        var json = MAPPER.createObjectNode();
        json.put("description", longText);
        var subjects = json.putArray("subjects");
        for (int i = 0; i < 20; i++) {
            subjects.add("s".repeat(500));
        }
        json.put("setting", longText).put("time_of_day", "day").put("mood", longText);
        script = n -> new Object[] {200, chat(json.toString())};

        Description d = client().describe(frames(1));

        assertEquals(Description.MAX_DESCRIPTION, d.description().length());
        assertEquals(Description.MAX_SUBJECTS, d.subjects().size());
        assertTrue(d.subjects().stream().allMatch(s -> s.length() == Description.MAX_SUBJECT));
        assertEquals(Description.MAX_FIELD, d.setting().length());
        assertEquals(Description.MAX_FIELD, d.mood().length());
    }

    @Test
    void stripsEscapeAndControlCharacters() throws Exception {
        var json = MAPPER.createObjectNode();
        json.put("description", "red \u001b[31mALERT\u001b[0m\u0007 line1\nline2\u009b2J ‮evil​");
        json.putArray("subjects").add("\u001b]0;title\u0007car");
        json.put("setting", "\u001b[2Jcity").put("time_of_day", "day").put("mood", "ok\r\n");
        script = n -> new Object[] {200, chat(json.toString())};

        Description d = client().describe(frames(1));

        String all = d.description() + d.subjects() + d.setting() + d.mood();
        assertTrue(all.chars().noneMatch(c -> Character.isISOControl(c) || c == 0x202e || c == 0x200b), all);
        assertEquals("red [31mALERT[0m line1 line22J evil", d.description());
        assertEquals("ok", d.mood());
    }

    @Test
    void timesOutAndGivesUpAfterBoundedRetries() throws Exception {
        delayMillis = 800;

        DescribeException e = assertThrows(DescribeException.class,
                () -> client(Duration.ofMillis(150)).describe(frames(1)));

        assertTrue(e.getMessage().contains("3 attempts"), e.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void retrySucceedsAfterTransientFailure() throws Exception {
        script = n -> n < 2 ? new Object[] {503, "busy"} : new Object[] {200, chat(GOOD)};

        Description d = client().describe(frames(1));

        assertEquals("A street at night", d.description());
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        script = n -> new Object[] {404, "{\"error\":\"model 'x' not found\"}"};

        DescribeException e = assertThrows(DescribeException.class, () -> client().describe(frames(1)));

        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void errorMessagesAreSanitized() throws Exception {
        script = n -> new Object[] {400, "bad \u001b[31mred\u001b[0m"};

        DescribeException e = assertThrows(DescribeException.class, () -> client().describe(frames(1)));

        assertFalse(e.getMessage().contains("\u001b"));
    }

    @Test
    void failsClearlyWhenOllamaIsUnreachable() throws Exception {
        int port = server.getAddress().getPort();
        server.stop(0);
        var down = new VisionClient(URI.create("http://127.0.0.1:" + port), "m",
                Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(5));

        DescribeException e = assertThrows(DescribeException.class, () -> down.describe(frames(1)));

        assertTrue(e.getMessage().contains("attempts"), e.getMessage());
    }

    @Test
    void rejectsEmptyFrameList() {
        assertThrows(DescribeException.class, () -> client().describe(List.of()));
    }
}
