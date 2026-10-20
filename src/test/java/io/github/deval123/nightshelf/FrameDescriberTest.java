package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real ffmpeg on clips generated at test time, with a local stub standing in for Ollama.
 * Skipped when ffmpeg is not installed.
 */
class FrameDescriberTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GOOD = """
            {"description": "A street at night", "subjects": ["car"], "setting": "city",
             "time_of_day": "night", "mood": "calm"}""";

    @TempDir
    Path library;

    private HttpServer server;
    private volatile int chatStatus = 200;
    private final AtomicInteger chatCalls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            chatCalls.incrementAndGet();
            String body = chatStatus == 200
                    ? MAPPER.createObjectNode().set("message", MAPPER.createObjectNode().put("content", GOOD)).toString()
                    : "no";
            reply(exchange, chatStatus, body);
        });
        server.createContext("/api/embed", exchange -> {
            exchange.getRequestBody().readAllBytes();
            reply(exchange, 200, "{\"embeddings\": [[0.5, 0.5]]}");
        });
        server.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ((ExecutorService) server.getExecutor()).shutdownNow();
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private Path video(String source, String name) throws Exception {
        assumeTrue(hasFfmpeg(), "ffmpeg not installed");
        Path out = library.resolve("clips").resolve(source).resolve(name);
        Files.createDirectories(out.getParent());
        Process p = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi",
                "-i", "testsrc=size=640x360:rate=10:duration=2", "-pix_fmt", "yuv420p", "-y", out.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        return out;
    }

    private static boolean hasFfmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static long frameDirs() throws IOException {
        try (var s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.filter(p -> p.getFileName().toString().startsWith("nightshelf-frames-")).count();
        }
    }

    private FrameDescriber describer() {
        return new FrameDescriber(new FrameExtractor(library, 3),
                new VisionClient(base(), "m", Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMillis(5)));
    }

    private static Clip clip(Path path) {
        String id = path.getFileName().toString().replace(".mp4", "");
        return new Clip("src", id, path, Files.exists(path) ? 1 : 0, 1_000, true, java.util.Map.of(), 0);
    }

    @Test
    void describesAClipAndDeletesItsFrames() throws Exception {
        Path v = video("src", "a.mp4");
        long before = frameDirs();

        Description d = describer().describe(clip(v));

        assertEquals("A street at night", d.description());
        assertEquals(before, frameDirs());
    }

    @Test
    void deletesFramesWhenTheModelFails() throws Exception {
        Path v = video("src", "a.mp4");
        chatStatus = 400;
        long before = frameDirs();

        assertThrows(DescribeException.class, () -> describer().describe(clip(v)));

        assertEquals(before, frameDirs());
    }

    @Test
    void fullPassOverRealClipsIsolatesTheBrokenOne() throws Exception {
        Path good = video("src", "good.mp4");
        Path other = video("src", "other.mp4");
        Path bad = Files.writeString(library.resolve("clips/src/bad.mp4"), "not a video");
        long before = frameDirs();
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        try (ClipIndex index = ClipIndex.open(library.resolve("db/index.db"))) {
            var runner = new BatchRunner(index, describer(), new EmbedClient(base(), "e", null), "m", "e",
                    new StopSignal(), System::nanoTime, new PrintStream(log, true, StandardCharsets.UTF_8));

            BatchRunner.Outcome o = runner.run(List.of(clip(bad), clip(good), clip(other)),
                    BatchRunner.Options.defaults());

            assertEquals(0, o.exitCode());
            assertEquals(2, o.run().described());
            assertEquals(1, o.run().failed());
            assertEquals(2, o.run().embedded());
            assertEquals(2, chatCalls.get(), "the broken clip never reached the model");
        }
        assertEquals(before, frameDirs());
        String text = log.toString(StandardCharsets.UTF_8);
        assertEquals(1, text.lines().filter(l -> l.startsWith("src/bad") && l.contains("failed")).count(), text);
    }
}
