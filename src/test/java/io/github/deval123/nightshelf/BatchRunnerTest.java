package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runner is driven with fake describers and embedders; no model, ffmpeg or network is involved. */
class BatchRunnerTest {

    private static final Description DESC = new Description("a street", List.of("car"), "city", "night", "calm");

    @TempDir
    Path dir;

    private final AtomicLong nanos = new AtomicLong();
    private final Map<String, Integer> describeCalls = new HashMap<>();
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(log, true, StandardCharsets.UTF_8);
    private StopSignal stop = new StopSignal();
    /** Keys that fail to be described; everything else succeeds after taking two (fake) seconds. */
    private Set<String> failing = Set.of();
    private Embedder embedder = text -> new float[] {1, 0};

    private ClipIndex index() {
        return ClipIndex.open(dir.resolve("index.db"));
    }

    private static List<Clip> clips(int n) {
        return IntStream.range(0, n).mapToObj(i -> clip(String.format("c%02d", i))).toList();
    }

    private static Clip clip(String id) {
        return new Clip("src", id, Path.of(id + ".mp4"), 1, 1_000, true, Map.of(), 0);
    }

    private ClipDescriber describer() {
        return clip -> {
            describeCalls.merge(clip.key(), 1, Integer::sum);
            nanos.addAndGet(2_000_000_000L);
            if (failing.contains(clip.key())) {
                throw new DescribeException("model said no", false, null);
            }
            return DESC;
        };
    }

    private BatchRunner runner(ClipIndex index, ClipDescriber describer) {
        return new BatchRunner(index, describer, embedder, "vision-m", "embed-m", stop, nanos::get, out);
    }

    private BatchRunner runner(ClipIndex index) {
        return runner(index, describer());
    }

    private String output() {
        return log.toString(StandardCharsets.UTF_8);
    }

    @Test
    void describesAndEmbedsEverythingInOnePassAndStoresTheRun() {
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index).run(clips(3), BatchRunner.Options.defaults());

            assertEquals(0, o.exitCode());
            RunRecord r = index.lastRun().orElseThrow();
            assertEquals(r, o.run());
            assertTrue(r.finished());
            assertEquals(3, r.described());
            assertEquals(0, r.failed());
            assertEquals(3, r.embedded());
            assertEquals("completed", r.stopReason());
            assertEquals("vision-m", r.visionModel());
            assertEquals("embed-m", r.embedModel());
            assertEquals(6.0, r.totalSeconds(), 1e-9);
            assertEquals(2.0, r.avgSecondsPerClip(), 1e-9);
            assertTrue(index.toProcess().isEmpty());
            assertTrue(index.toEmbed("embed-m").isEmpty());
            assertEquals(3, index.totals("embed-m").embedded());

            String text = output();
            assertTrue(text.contains("src/c00  2.0s  ok"), text);
            assertTrue(text.contains("run completed: described 3, failed 0, left alone 0, embedded 3"), text);
        }
    }

    @Test
    void aFailingClipIsRecordedAndTheRunContinues() {
        failing = Set.of("src/c01");
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index).run(clips(3), BatchRunner.Options.defaults());

            assertEquals(0, o.exitCode());
            assertEquals(2, o.run().described());
            assertEquals(1, o.run().failed());
            StoredClip c1 = index.get("src/c01").orElseThrow();
            assertEquals(StoredClip.Status.FAILED, c1.status());
            assertEquals("model said no", c1.failureReason());
            assertEquals(StoredClip.Status.DESCRIBED, index.get("src/c02").orElseThrow().status());
            assertTrue(output().contains("src/c01  2.0s  failed: model said no"), output());
            assertEquals(List.of("src/c01"), index.toProcess(), "retried at the next run");
        }
    }

    @Test
    void anUnexpectedExceptionIsAFailureWithoutDetails() {
        ClipDescriber boom = clip -> {
            throw new IllegalStateException("secret internals");
        };
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index, boom).run(clips(2), BatchRunner.Options.defaults());

            assertEquals(2, o.run().failed());
            assertEquals("unexpected error (IllegalStateException)",
                    index.get("src/c00").orElseThrow().failureReason());
            assertFalse(output().contains("secret internals"));
        }
    }

    @Test
    void abortsWithExitCodeThreeAfterTenFailuresInARow() {
        failing = Set.copyOf(clips(15).stream().map(Clip::key).toList());
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index).run(clips(15), BatchRunner.Options.defaults());

            assertEquals(3, o.exitCode());
            assertEquals(10, o.run().failed());
            assertEquals("too-many-failures", o.run().stopReason());
            assertTrue(o.message().contains("10 clips in a row"), o.message());
            assertEquals(15, index.toProcess().size(), "failed clips are retried later, the rest stays pending");
            assertEquals(0, describeCalls.getOrDefault("src/c10", 0));
        }
    }

    @Test
    void aSuccessResetsTheConsecutiveFailureCount() {
        failing = Set.copyOf(IntStream.range(0, 19).filter(i -> i != 9)
                .mapToObj(i -> String.format("src/c%02d", i)).toList());
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index).run(clips(19), BatchRunner.Options.defaults());

            assertEquals(0, o.exitCode());
            assertEquals(18, o.run().failed());
            assertEquals(1, o.run().described());
        }
    }

    @Test
    void refusesAMassRemovalUnlessAllowed() {
        try (ClipIndex index = index()) {
            runner(index).run(clips(10), BatchRunner.Options.defaults());
            describeCalls.clear();

            BatchRunner.Outcome refused = runner(index).run(clips(4), BatchRunner.Options.defaults());

            assertEquals(2, refused.exitCode());
            assertNull(refused.run());
            assertTrue(refused.message().contains("6 of 10"), refused.message());
            assertEquals(10, index.totals("embed-m").present(), "nothing was flagged removed");

            BatchRunner.Outcome allowed = runner(index).run(clips(4),
                    new BatchRunner.Options(null, null, false, false, true));

            assertEquals(0, allowed.exitCode());
            assertEquals(4, index.totals("embed-m").present());
            assertEquals(6, index.totals("embed-m").removed());
        }
    }

    @Test
    void exactlyHalfMissingIsNotAMassRemoval() {
        try (ClipIndex index = index()) {
            runner(index).run(clips(10), BatchRunner.Options.defaults());

            BatchRunner.Outcome o = runner(index).run(clips(5), BatchRunner.Options.defaults());

            assertEquals(0, o.exitCode());
            assertEquals(5, index.totals("embed-m").removed());
        }
    }

    @Test
    void refusesAnEmptyScanUnlessAllowed() {
        try (ClipIndex index = index()) {
            runner(index).run(clips(3), BatchRunner.Options.defaults());

            BatchRunner.Outcome refused = runner(index).run(List.of(), BatchRunner.Options.defaults());
            assertEquals(2, refused.exitCode());
            assertTrue(refused.message().contains("no clips"), refused.message());
            assertEquals(3, index.totals("embed-m").present());

            BatchRunner.Outcome allowed = runner(index).run(List.of(),
                    new BatchRunner.Options(null, null, false, false, true));
            assertEquals(0, allowed.exitCode());
            assertEquals(3, index.totals("embed-m").removed());
        }
    }

    @Test
    void aClipThatFailedInThreeRunsIsLeftAloneUntilRetryFailed() {
        failing = Set.of("src/c00");
        try (ClipIndex index = index()) {
            for (int run = 1; run <= 3; run++) {
                runner(index).run(clips(2), BatchRunner.Options.defaults());
            }
            assertEquals(3, describeCalls.get("src/c00"));
            assertEquals(1, index.totals("embed-m").givenUp());

            BatchRunner.Outcome fourth = runner(index).run(clips(2), BatchRunner.Options.defaults());

            assertEquals(3, describeCalls.get("src/c00"), "left alone");
            assertEquals(1, fourth.run().skipped());
            assertEquals(0, fourth.run().failed());

            BatchRunner.Outcome forced = runner(index).run(clips(2),
                    new BatchRunner.Options(null, null, false, true, false));

            assertEquals(4, describeCalls.get("src/c00"), "--retry-failed tries it again");
            assertEquals(1, forced.run().failed());
            assertEquals(0, forced.run().skipped());

            failing = Set.of();
            runner(index).run(clips(2), new BatchRunner.Options(null, null, false, true, false));
            assertEquals(0, index.totals("embed-m").failed());
            assertEquals(0, index.totals("embed-m").givenUp());
        }
    }

    @Test
    void maxClipsStopsAfterThatManyButStillEmbeds() {
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index).run(clips(5), new BatchRunner.Options(2, null, false, false, false));

            assertEquals(0, o.exitCode());
            assertEquals(2, o.run().described());
            assertEquals(2, o.run().embedded());
            assertEquals("max-clips", o.run().stopReason());
            assertEquals(3, index.toProcess().size());
        }
    }

    @Test
    void timeBudgetStopsCleanlyBetweenClips() {
        ClipDescriber slow = clip -> {
            nanos.addAndGet(60_000_000_000L);
            return DESC;
        };
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index, slow).run(clips(5),
                    new BatchRunner.Options(null, java.time.Duration.ofMinutes(2), false, false, false));

            assertEquals(0, o.exitCode());
            assertEquals(2, o.run().described());
            assertEquals("time-budget", o.run().stopReason());
            assertEquals(3, index.toProcess().size());
            assertEquals(0, o.run().embedded(), "embedding the rest waits for the next run");
        }
    }

    @Test
    void aStopSignalFinishesTheCurrentClipRecordsItAndExitsCleanly() {
        List<Clip> clips = clips(4);
        ClipDescriber stopsDuringSecondClip = clip -> {
            describeCalls.merge(clip.key(), 1, Integer::sum);
            if (clip.key().equals("src/c01")) {
                stop.request();
            }
            return DESC;
        };
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index, stopsDuringSecondClip).run(clips, BatchRunner.Options.defaults());

            assertEquals(0, o.exitCode());
            assertEquals("signal", o.run().stopReason());
            assertEquals(2, o.run().described());
            assertEquals(StoredClip.Status.DESCRIBED, index.get("src/c01").orElseThrow().status());
            assertEquals(List.of("src/c02", "src/c03"), index.toProcess());
            assertFalse(describeCalls.containsKey("src/c02"));
            assertTrue(output().contains("run signal:"), output());
        }
    }

    @Test
    void aHardKillResumesWithOnlyTheUnfinishedClips() {
        List<Clip> clips = clips(5);
        ClipDescriber dies = clip -> {
            describeCalls.merge(clip.key(), 1, Integer::sum);
            if (clip.key().equals("src/c02")) {
                throw new AssertionError("process killed");
            }
            return DESC;
        };
        try (ClipIndex index = index()) {
            assertThrows(AssertionError.class, () -> runner(index, dies).run(clips, BatchRunner.Options.defaults()));
        }
        assertEquals(Map.of("src/c00", 1, "src/c01", 1, "src/c02", 1), describeCalls);
        describeCalls.clear();

        try (ClipIndex index = index()) {
            assertFalse(index.lastRun().orElseThrow().finished(), "the interrupted run is visible as unfinished");
            assertEquals(List.of("src/c02", "src/c03", "src/c04"), index.toProcess());

            BatchRunner.Outcome o = runner(index).run(clips, BatchRunner.Options.defaults());

            assertEquals(0, o.exitCode());
            assertEquals(3, o.run().described());
            assertEquals(Map.of("src/c02", 1, "src/c03", 1, "src/c04", 1), describeCalls);
            assertTrue(o.run().finished());
            assertEquals(2, o.run().id());
        }
    }

    @Test
    void noClipIsDescribedTwiceInOneRun() {
        failing = Set.of("src/c01");
        List<Clip> withDuplicates = new ArrayList<>(clips(3));
        withDuplicates.addAll(clips(3));
        try (ClipIndex index = index()) {
            runner(index).run(withDuplicates, new BatchRunner.Options(null, null, false, true, false));

            assertEquals(Map.of("src/c00", 1, "src/c01", 1, "src/c02", 1), describeCalls);
        }
    }

    @Test
    void skipEmbedLeavesEmbeddingForLater() {
        try (ClipIndex index = index()) {
            BatchRunner runner = new BatchRunner(index, describer(), null, "vision-m", "embed-m", stop, nanos::get, out);

            BatchRunner.Outcome o = runner.run(clips(2), new BatchRunner.Options(null, null, true, false, false));

            assertEquals(2, o.run().described());
            assertEquals(0, o.run().embedded());
            assertEquals(2, index.toEmbed("embed-m").size());
        }
    }

    @Test
    void anEmbeddingFailureIsIsolatedAndRetriedNextTime() {
        embedder = text -> {
            throw new EmbedException("nope", false, null);
        };
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index).run(clips(3), BatchRunner.Options.defaults());

            assertEquals(0, o.exitCode());
            assertEquals(3, o.run().described());
            assertEquals(0, o.run().embedded());
            assertEquals(3, index.toEmbed("embed-m").size());
            assertTrue(output().contains("embed failed src/c00: nope"), output());
        }
    }

    @Test
    void tenEmbeddingFailuresInARowAbortWithExitCodeThree() {
        embedder = text -> {
            throw new EmbedException("down", false, null);
        };
        try (ClipIndex index = index()) {
            BatchRunner.Outcome o = runner(index).run(clips(12), BatchRunner.Options.defaults());

            assertEquals(3, o.exitCode());
            assertEquals(12, o.run().described(), "descriptions were all saved");
        }
    }

    @Test
    void progressLinesNeverCarryControlCharacters() {
        ClipDescriber hostile = clip -> {
            throw new DescribeException("bad \u001b[31mred", false, null);
        };
        try (ClipIndex index = index()) {
            runner(index, hostile).run(clips(1), BatchRunner.Options.defaults());

            assertFalse(output().contains("\u001b"));
        }
    }
}
