package io.github.deval123.nightshelf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Extracts evenly spaced, downscaled JPEG frames from a clip with ffprobe and
 * ffmpeg. Processes are started from argument lists, never through a shell.
 */
public final class FrameExtractor {

    static final int MAX_SIDE = 896;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final Path clipbankDir;
    private final int frameCount;
    private final Duration timeout;
    private final String ffmpeg;
    private final String ffprobe;

    public FrameExtractor(Path clipbankDir, int frameCount) {
        this(clipbankDir, frameCount, DEFAULT_TIMEOUT, "ffmpeg", "ffprobe");
    }

    FrameExtractor(Path clipbankDir, int frameCount, Duration timeout, String ffmpeg, String ffprobe) {
        if (frameCount < 1) {
            throw new IllegalArgumentException("frameCount must be at least 1: " + frameCount);
        }
        this.clipbankDir = clipbankDir;
        this.frameCount = frameCount;
        this.timeout = timeout;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
    }

    /**
     * Extracts the frames of one clip. The caller must close the result.
     *
     * @throws FrameException if this clip cannot be processed; nothing is left on disk
     */
    public ClipFrames extract(Path clip) throws FrameException {
        Path safe = validate(clip);
        double duration = probeDuration(safe);

        Path dir;
        try {
            dir = Files.createTempDirectory("nightshelf-frames-");
        } catch (IOException e) {
            throw new FrameException(clip, "cannot create temporary directory", e);
        }
        try {
            List<Path> frames = new ArrayList<>();
            List<Double> times = timestamps(duration, frameCount);
            for (int i = 0; i < times.size(); i++) {
                Path out = dir.resolve("frame_" + (i + 1) + ".jpg");
                run(clip, frameCommand(safe, times.get(i), out));
                if (!Files.isRegularFile(out) || sizeOf(out) == 0) {
                    throw new FrameException(clip, "no frame produced at " + times.get(i) + "s");
                }
                frames.add(out);
            }
            return new ClipFrames(dir, List.copyOf(frames));
        } catch (FrameException | RuntimeException e) {
            ClipFrames.deleteQuietly(dir);
            throw e;
        }
    }

    Path validate(Path clip) throws FrameException {
        return ClipPaths.requireInside(clipbankDir, clip);
    }

    private double probeDuration(Path safe) throws FrameException {
        String out = run(safe, probeCommand(safe)).strip();
        double seconds;
        try {
            seconds = Double.parseDouble(out);
        } catch (NumberFormatException e) {
            throw new FrameException(safe, "no usable duration (ffprobe said: " + snippet(out) + ")");
        }
        if (!Double.isFinite(seconds) || seconds <= 0) {
            throw new FrameException(safe, "clip has zero length");
        }
        return seconds;
    }

    List<String> probeCommand(Path clip) {
        // ffprobe has no -nostdin option; run() closes its stdin instead.
        return List.of(ffprobe, "-v", "error",
                "-show_entries", "format=duration", "-of", "csv=p=0", "-i", clip.toString());
    }

    List<String> frameCommand(Path clip, double seconds, Path out) {
        String scale = "scale=w='min(" + MAX_SIDE + ",iw)':h='min(" + MAX_SIDE + ",ih)'"
                + ":force_original_aspect_ratio=decrease";
        return List.of(ffmpeg, "-nostdin", "-v", "error",
                "-ss", String.format(java.util.Locale.ROOT, "%.3f", seconds),
                "-i", clip.toString(),
                "-frames:v", "1", "-vf", scale, "-q:v", "3", "-y", out.toString());
    }

    /** Timestamps at i/(n+1) of the duration: 25, 50 and 75 percent for n = 3. */
    static List<Double> timestamps(double duration, int n) {
        List<Double> times = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            times.add(duration * i / (n + 1));
        }
        return times;
    }

    /** Runs a command with a timeout and returns its combined output. */
    private String run(Path clip, List<String> command) throws FrameException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new FrameException(clip, "cannot start " + command.get(0), e);
        }
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new FrameException(clip, command.get(0) + " timed out after " + timeout.toSeconds() + "s");
            }
            String text = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new FrameException(clip, command.get(0) + " failed (exit " + process.exitValue()
                        + "): " + snippet(text));
            }
            return text;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new FrameException(clip, "interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            process.destroyForcibly();
            throw new FrameException(clip, "cannot read " + command.get(0) + " output", e);
        }
    }

    private static long sizeOf(Path p) throws FrameException {
        try {
            return Files.size(p);
        } catch (IOException e) {
            throw new FrameException(p, "cannot read frame", e);
        }
    }

    private static String snippet(String text) {
        String t = text.strip().replace('\n', ' ');
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }
}
