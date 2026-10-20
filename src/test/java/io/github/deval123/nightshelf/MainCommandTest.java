package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the real entry point in a child JVM, because the command line ends in System.exit. */
class MainCommandTest {

    @TempDir
    Path home;

    private record Result(int exit, String out, String err) {
    }

    private Result run(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Main.class.getName()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().keySet().removeIf(k -> k.equals("CLIPBANK_DIR") || k.equals("OLLAMA_URL")
                || k.equals("DB_PATH") || k.equals("FRAME_COUNT"));
        pb.environment().put("DB_PATH", home.resolve("index.db").toString());
        Process p = pb.start();
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        byte[] err = p.getErrorStream().readAllBytes();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }

    @Test
    void unknownCommandPrintsOneLineAndExitsWithTwo() throws Exception {
        Result r = run("frobnicate");

        assertEquals(2, r.exit());
        assertEquals("unknown command: frobnicate; try: scan, describe, sync, run, stats, embed, search",
                r.err().strip());
        assertEquals("", r.out());
    }

    @Test
    void theCommandNameInTheMessageIsSanitized() throws Exception {
        Result r = run("\u001b[31mred");

        assertEquals(2, r.exit());
        assertTrue(!r.err().contains("\u001b"), r.err());
        assertEquals(1, r.err().strip().lines().count());
    }

    @Test
    void knownCommandsWithWrongArgumentsGetAUsageLine() throws Exception {
        Result r = run("describe");

        assertEquals(2, r.exit());
        assertEquals("usage: describe <clip.mp4>", r.err().strip());
    }

    @Test
    void noArgumentsStillPrintTheConfiguration() throws Exception {
        Result r = run();

        assertEquals(0, r.exit());
        assertTrue(r.out().contains("CLIPBANK_DIR=<unset>"), r.out());
    }
}
