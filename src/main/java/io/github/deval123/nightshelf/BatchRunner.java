package io.github.deval123.nightshelf;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One resumable pass: sync the index with the scan, describe every pending or
 * failed clip, then embed the described clips that have no current vector.
 * Every clip result is committed on its own, so a kill at any point loses at
 * most the clip being processed. A clip is described at most once per run.
 */
public final class BatchRunner {

    /** Failures in a row after which the run gives up: Ollama is probably down. */
    static final int MAX_CONSECUTIVE_FAILURES = 10;

    public static final int EXIT_OK = 0;
    public static final int EXIT_REFUSED = 2;
    public static final int EXIT_TOO_MANY_FAILURES = 3;

    public record Options(Integer maxClips, Duration timeBudget, boolean skipEmbed, boolean retryFailed,
            boolean allowMassRemoval) {

        public static Options defaults() {
            return new Options(null, null, false, false, false);
        }
    }

    /** @param run the stored record of this run, null if the run was refused before it began */
    public record Outcome(int exitCode, RunRecord run, String message) {
    }

    private final ClipIndex index;
    private final ClipDescriber describer;
    private final Embedder embedder;
    private final String visionModel;
    private final String embedModel;
    private final StopSignal stop;
    private final LongSupplier nanoTime;
    private final PrintStream out;

    /** @param embedder may be null when embedding is skipped */
    public BatchRunner(ClipIndex index, ClipDescriber describer, Embedder embedder, String visionModel,
            String embedModel, StopSignal stop, LongSupplier nanoTime, PrintStream out) {
        this.index = index;
        this.describer = describer;
        this.embedder = embedder;
        this.visionModel = visionModel;
        this.embedModel = embedModel;
        this.stop = stop;
        this.nanoTime = nanoTime;
        this.out = out;
    }

    public Outcome run(List<Clip> scanned, Options options) {
        String refusal = guard(scanned, options);
        if (refusal != null) {
            return new Outcome(EXIT_REFUSED, null, refusal);
        }
        long runStart = nanoTime.getAsLong();
        index.sync(scanned);
        long runId = index.startRun(visionModel, embedModel);

        Map<String, Clip> byKey = scanned.stream()
                .collect(Collectors.toMap(Clip::key, Function.identity(), (a, b) -> a));
        List<String> todo = new ArrayList<>(index.toProcess(options.retryFailed()));
        int skipped = options.retryFailed() ? 0 : index.totals(embedModel).givenUp();

        Counts counts = new Counts();
        String reason = describePhase(todo, byKey, options, runStart, counts);
        if (reason.equals("completed") || reason.equals("max-clips")) {
            String embedReason = options.skipEmbed() ? "completed" : embedPhase(options, runStart, counts);
            if (!embedReason.equals("completed")) {
                reason = embedReason;
            }
        }

        double seconds = (nanoTime.getAsLong() - runStart) / 1e9;
        int processed = counts.described + counts.failed;
        index.finishRun(runId, counts.described, counts.failed, skipped, counts.embedded, seconds,
                processed == 0 ? 0 : counts.clipSeconds / processed, reason);
        RunRecord record = index.lastRun().orElseThrow();
        printSummary(record);
        if (reason.equals("too-many-failures")) {
            String message = "stopping: " + MAX_CONSECUTIVE_FAILURES + " clips in a row failed;"
                    + " is Ollama reachable? Failed clips are retried at the next run.";
            return new Outcome(EXIT_TOO_MANY_FAILURES, record, message);
        }
        return new Outcome(EXIT_OK, record, null);
    }

    /** An unmounted library must never look like a mass deletion. */
    private String guard(List<Clip> scanned, Options options) {
        if (options.allowMassRemoval()) {
            return null;
        }
        if (scanned.isEmpty()) {
            return "the scan found no clips; is the library mounted? Use --allow-mass-removal to continue anyway.";
        }
        ChangeDetector.Changes preview = index.preview(scanned);
        int indexed = preview.changed().size() + preview.unchanged().size() + preview.removed().size();
        if (preview.removed().size() * 2 > indexed) {
            return preview.removed().size() + " of " + indexed + " indexed clips are missing from the scan;"
                    + " is the library mounted? Use --allow-mass-removal to continue anyway.";
        }
        return null;
    }

    private String describePhase(List<String> todo, Map<String, Clip> byKey, Options options, long runStart,
            Counts counts) {
        Set<String> seen = new HashSet<>();
        int consecutive = 0;
        for (String key : todo) {
            Clip clip = byKey.get(key);
            if (clip == null || !seen.add(key)) {
                continue;
            }
            if (options.maxClips() != null && counts.described + counts.failed >= options.maxClips()) {
                return "max-clips";
            }
            String stopReason = stopReason(options, runStart);
            if (stopReason != null) {
                return stopReason;
            }
            long start = nanoTime.getAsLong();
            String failure = null;
            try {
                Description d = describer.describe(clip);
                index.recordDescribed(key, d);
            } catch (IOException e) {
                failure = e.getMessage();
            } catch (IndexException e) {
                throw e;
            } catch (RuntimeException e) {
                failure = "unexpected error (" + e.getClass().getSimpleName() + ")";
            }
            double seconds = (nanoTime.getAsLong() - start) / 1e9;
            counts.clipSeconds += seconds;
            if (failure == null) {
                counts.described++;
                consecutive = 0;
                progress(key, seconds, null);
            } else {
                index.recordFailed(key, failure);
                counts.failed++;
                progress(key, seconds, failure);
                if (++consecutive >= MAX_CONSECUTIVE_FAILURES) {
                    return "too-many-failures";
                }
            }
        }
        return "completed";
    }

    private String embedPhase(Options options, long runStart, Counts counts) {
        int consecutive = 0;
        for (String key : index.toEmbed(embedModel)) {
            String stopReason = stopReason(options, runStart);
            if (stopReason != null) {
                return stopReason;
            }
            try {
                String text = index.get(key).orElseThrow().description().searchText();
                index.recordEmbedding(key, embedModel, embedder.embed(text));
                counts.embedded++;
                consecutive = 0;
            } catch (EmbedException e) {
                counts.embedFailed++;
                out.println("embed failed " + key + ": " + e.getMessage());
                out.flush();
                if (++consecutive >= MAX_CONSECUTIVE_FAILURES) {
                    return "too-many-failures";
                }
            }
        }
        return "completed";
    }

    private String stopReason(Options options, long runStart) {
        if (stop.requested()) {
            return "signal";
        }
        if (options.timeBudget() != null
                && nanoTime.getAsLong() - runStart >= options.timeBudget().toNanos()) {
            return "time-budget";
        }
        return null;
    }

    private void progress(String key, double seconds, String failure) {
        out.println(String.format(Locale.ROOT, "%s  %.1fs  %s", key, seconds,
                failure == null ? "ok" : "failed: " + Sanitize.clean(failure, 100)));
        out.flush();
    }

    private void printSummary(RunRecord r) {
        out.println(String.format(Locale.ROOT,
                "run %s: described %d, failed %d, left alone %d, embedded %d; %.1fs in total, %.1fs per described clip",
                r.stopReason(), r.described(), r.failed(), r.skipped(), r.embedded(), r.totalSeconds(),
                r.avgSecondsPerClip()));
        out.flush();
    }

    private static final class Counts {
        int described;
        int failed;
        int embedded;
        int embedFailed;
        double clipSeconds;
    }
}
