package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameExtractorTest {

    @TempDir
    Path library;

    private FrameExtractor extractor(int n) {
        return new FrameExtractor(library, n);
    }

    @Test
    void timestampsAreEvenlySpaced() {
        List<Double> t = FrameExtractor.timestamps(8.0, 3);

        assertEquals(List.of(2.0, 4.0, 6.0), t);
        assertEquals(List.of(5.0), FrameExtractor.timestamps(10.0, 1));
    }

    @Test
    void probeCommandIsAnArgumentListWithoutShell() {
        List<String> cmd = extractor(3).probeCommand(Path.of("/lib/a b; rm -rf x.mp4"));

        assertEquals("ffprobe", cmd.get(0));
        assertEquals("/lib/a b; rm -rf x.mp4", cmd.get(cmd.size() - 1));
        assertFalse(cmd.contains("sh") || cmd.contains("-c"));
    }

    @Test
    void frameCommandSeeksScalesAndDisablesStdin() {
        List<String> cmd = extractor(3).frameCommand(Path.of("/lib/c.mp4"), 2.5, Path.of("/tmp/f.jpg"));

        assertEquals("ffmpeg", cmd.get(0));
        assertEquals("-nostdin", cmd.get(1));
        assertEquals("2.500", cmd.get(cmd.indexOf("-ss") + 1));
        assertTrue(cmd.indexOf("-ss") < cmd.indexOf("-i"));
        assertEquals("/lib/c.mp4", cmd.get(cmd.indexOf("-i") + 1));
        assertTrue(cmd.get(cmd.indexOf("-vf") + 1).contains("min(896,iw)"));
        assertEquals("/tmp/f.jpg", cmd.get(cmd.size() - 1));
    }

    @Test
    void rejectsZeroFrameCount() {
        assertThrows(IllegalArgumentException.class, () -> extractor(0));
    }

    @Test
    void rejectsPathStartingWithDash() {
        assertThrows(FrameException.class, () -> extractor(3).extract(Path.of("-evil.mp4")));
        assertThrows(FrameException.class, () -> extractor(3).extract(library.resolve("-evil.mp4")));
    }

    @Test
    void rejectsPathOutsideLibrary() throws IOException {
        Path outside = Files.createTempFile("outside", ".mp4");
        try {
            FrameException e = assertThrows(FrameException.class, () -> extractor(3).extract(outside));
            assertTrue(e.getMessage().contains("outside the clip library"), e.getMessage());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsSymlinkPointingOutsideLibrary() throws IOException {
        Path outside = Files.createTempFile("outside", ".mp4");
        try {
            Path link = Files.createSymbolicLink(library.resolve("link.mp4"), outside);
            assertThrows(FrameException.class, () -> extractor(3).extract(link));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void reportsMissingFile() {
        assertThrows(FrameException.class, () -> extractor(3).extract(library.resolve("nope.mp4")));
    }

    @Test
    void killsProcessThatExceedsTimeout() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"));
        Path slow = library.resolve("slow-ffprobe.sh");
        Files.writeString(slow, "#!/bin/sh\nsleep 30\n");
        assertTrue(slow.toFile().setExecutable(true));
        Path clip = Files.writeString(library.resolve("c.mp4"), "x");
        var timed = new FrameExtractor(library, 3, Duration.ofMillis(300), "ffmpeg", slow.toString());

        long start = System.nanoTime();
        FrameException e = assertThrows(FrameException.class, () -> timed.extract(clip));

        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);
    }

    /** Needs ffmpeg: the test video is generated at test time, nothing is committed. */
    @Nested
    class WithFfmpeg {

        private Path video(String name, int width, int height, int seconds) throws Exception {
            assumeTrue(hasFfmpeg(), "ffmpeg not installed");
            Path out = library.resolve(name);
            Process p = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi",
                    "-i", "testsrc=size=" + width + "x" + height + ":rate=10:duration=" + seconds,
                    "-pix_fmt", "yuv420p", "-y", out.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assertEquals(0, p.waitFor());
            return out;
        }

        private boolean hasFfmpeg() {
            try {
                Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                return p.waitFor() == 0;
            } catch (Exception e) {
                return false;
            }
        }

        @Test
        void extractsDownscaledJpegFramesAndCleansUp() throws Exception {
            Path clip = video("wide.mp4", 1920, 1080, 4);
            Path dir;

            try (ClipFrames frames = extractor(3).extract(clip)) {
                dir = frames.dir();
                assertEquals(3, frames.frames().size());
                for (Path f : frames.frames()) {
                    BufferedImage img = ImageIO.read(f.toFile());
                    assertEquals(896, Math.max(img.getWidth(), img.getHeight()));
                }
                assertTrue(Files.isDirectory(dir));
            }

            assertFalse(Files.exists(dir));
        }

        @Test
        void doesNotUpscaleSmallClipsAndHandlesPortrait() throws Exception {
            Path clip = video("small.mp4", 320, 568, 2);

            try (ClipFrames frames = extractor(2).extract(clip)) {
                BufferedImage img = ImageIO.read(frames.frames().get(0).toFile());
                assertEquals(320, img.getWidth());
                assertEquals(568, img.getHeight());
            }
        }

        @Test
        void unreadableClipFailsOnlyThatClip() throws Exception {
            assumeTrue(hasFfmpeg(), "ffmpeg not installed");
            Path bad = Files.writeString(library.resolve("bad.mp4"), "not a video");
            Path good = video("good.mp4", 640, 360, 2);

            FrameException e = assertThrows(FrameException.class, () -> extractor(3).extract(bad));
            assertTrue(e.getMessage().contains("bad.mp4"), e.getMessage());
            try (ClipFrames frames = extractor(3).extract(good)) {
                assertEquals(3, frames.frames().size());
            }
        }

        @Test
        void emptyFileProducesClearError() throws Exception {
            assumeTrue(hasFfmpeg(), "ffmpeg not installed");
            Path empty = Files.createFile(library.resolve("empty.mp4"));

            assertThrows(FrameException.class, () -> extractor(3).extract(empty));
        }

        @Test
        void failureMidwayLeavesNoTemporaryDirectory() throws Exception {
            Path clip = video("c.mp4", 640, 360, 2);
            Path failing = library.resolve("fake-ffmpeg.sh");
            Files.writeString(failing, "#!/bin/sh\nexit 1\n");
            assertTrue(failing.toFile().setExecutable(true));
            var broken = new FrameExtractor(library, 3, Duration.ofSeconds(30), failing.toString(), "ffprobe");
            long before = countTempDirs();

            assertThrows(FrameException.class, () -> broken.extract(clip));

            assertEquals(before, countTempDirs());
        }

        private long countTempDirs() throws IOException {
            try (var s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
                return s.filter(p -> p.getFileName().toString().startsWith("nightshelf-frames-")).count();
            }
        }
    }
}
