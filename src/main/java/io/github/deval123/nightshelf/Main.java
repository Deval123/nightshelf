package io.github.deval123.nightshelf;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Main {

    private Main() {
    }

    static final List<String> COMMANDS = List.of("scan", "describe", "sync", "run", "stats", "embed", "search", "export");

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && !COMMANDS.contains(args[0])) {
            fail("unknown command: " + Sanitize.clean(args[0], 40) + "; try: " + String.join(", ", COMMANDS));
            return;
        }
        Config config;
        try {
            config = Config.fromEnv();
        } catch (IllegalArgumentException e) {
            fail("Invalid configuration: " + e.getMessage());
            return;
        }
        try {
            if (args.length > 0 && args[0].equals("scan")) {
                scan(config);
            } else if (args.length > 0 && args[0].equals("sync")) {
                sync(config);
            } else if (args.length > 0 && args[0].equals("run")) {
                run(config, RunArgs.parse(List.of(args).subList(1, args.length)));
            } else if (args.length == 1 && args[0].equals("stats")) {
                stats(config);
            } else if (args.length > 0 && args[0].equals("embed")) {
                embed(config);
            } else if (args.length > 0 && args[0].equals("search")) {
                search(config, SearchArgs.parse(List.of(args).subList(1, args.length)));
            } else if (args.length > 0 && args[0].equals("export")) {
                export(config, ExportArgs.parse(List.of(args).subList(1, args.length)));
            } else if (args.length == 2 && args[0].equals("describe")) {
                describe(config, Path.of(args[1]));
            } else if (args.length == 0) {
                System.out.println(config);
            } else {
                fail("usage: " + args[0] + (args[0].equals("describe") ? " <clip.mp4>" : ""));
            }
        } catch (IllegalArgumentException | IndexException | IOException e) {
            fail("Error: " + e.getMessage());
        }
    }

    /** Scans the library and updates the index; no model is called. */
    private static void sync(Config config) throws IOException {
        requireClipbank(config);
        var clips = LibraryScanner.scan(config.clipbankDir());
        try (ClipIndex index = ClipIndex.open(config.dbPath())) {
            var changes = index.sync(clips);
            System.out.println(changes.added().size() + " added, " + changes.changed().size() + " changed, "
                    + changes.unchanged().size() + " unchanged, " + changes.removed().size() + " removed; "
                    + index.toProcess().size() + " to describe");
        }
    }

    private static void scan(Config config) throws IOException {
        requireClipbank(config);
        var clips = LibraryScanner.scan(config.clipbankDir());
        long uncatalogued = clips.stream().filter(c -> !c.catalogued()).count();
        System.out.println(clips.size() + " clips (" + uncatalogued + " not in catalog)");
    }

    /** The full pass: scan, sync, describe, embed. Stops cleanly on SIGINT and SIGTERM. */
    private static void run(Config config, RunArgs args) throws IOException {
        requireClipbank(config);
        URI url = config.requireOllamaUrl();
        Catalog catalog = LibraryScanner.loadCatalog(config.clipbankDir());
        List<Clip> clips = LibraryScanner.scan(config.clipbankDir(), catalog);
        StopSignal stop = new StopSignal();
        Thread hook = new Thread(() -> {
            if (stop.isFinished()) {
                return;
            }
            stop.request();
            System.err.println("stop requested: finishing the current clip");
            try {
                stop.awaitFinished();
            } catch (InterruptedException e) {
                return;
            }
            // a clean stop is a success; halt skips the JVM's 130/143 exit status
            Runtime.getRuntime().halt(0);
        }, "stop-on-signal");
        Runtime.getRuntime().addShutdownHook(hook);
        int exit;
        try (ClipIndex index = ClipIndex.open(config.dbPath())) {
            var extractor = new FrameExtractor(config.clipbankDir(), config.frameCount());
            var vision = new VisionClient(url, config.visionModel());
            Embedder embedder = args.skipEmbed() ? null
                    : new EmbedClient(url, config.embedModel(), index.embeddingDimension(config.embedModel()));
            var runner = new BatchRunner(index, new FrameDescriber(extractor, vision), embedder,
                    config.visionModel(), config.embedModel(), stop, System::nanoTime, System.out);
            var outcome = runner.run(clips, new BatchRunner.Options(args.maxClips(), args.timeBudget(),
                    args.skipEmbed(), args.retryFailed(), args.allowMassRemoval()));
            if (outcome.message() != null) {
                System.err.println(outcome.message());
            }
            exit = outcome.exitCode();
        } finally {
            stop.markFinished();
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }

    private static void stats(Config config) {
        try (ClipIndex index = ClipIndex.open(config.dbPath())) {
            index.lastRun().ifPresentOrElse(r -> {
                System.out.println("last run #" + r.id() + " started " + java.time.Instant.ofEpochMilli(r.startedAt())
                        + (r.finished() ? ", " + r.stopReason() : ", not finished (interrupted?)"));
                System.out.println(String.format(Locale.ROOT,
                        "  described %d, failed %d, left alone %d, embedded %d; %.1fs in total, %.1fs per described clip",
                        r.described(), r.failed(), r.skipped(), r.embedded(), r.totalSeconds(), r.avgSecondsPerClip()));
                System.out.println("  models: " + printable(r.visionModel()) + ", " + printable(r.embedModel()));
            }, () -> System.out.println("no run yet"));
            var t = index.totals(config.embedModel());
            System.out.println("index: " + t.present() + " clips, " + t.described() + " described, " + t.pending()
                    + " pending, " + t.failed() + " failed (" + t.givenUp() + " given up after "
                    + ClipIndex.MAX_ATTEMPTS + " runs), " + t.embedded() + " embedded with " + printable(config.embedModel())
                    + ", " + t.removed() + " removed");
        }
    }

    private static final int MAX_CONSECUTIVE_EMBED_FAILURES = 5;

    /** Embeds every described clip that has no vector for the current embedding model. */
    private static void embed(Config config) {
        URI url = config.requireOllamaUrl();
        try (ClipIndex index = ClipIndex.open(config.dbPath())) {
            String model = config.embedModel();
            var client = new EmbedClient(url, model, index.embeddingDimension(model));
            List<String> keys = index.toEmbed(model);
            int done = 0;
            int failed = 0;
            int inARow = 0;
            for (String key : keys) {
                try {
                    index.recordEmbedding(key, model, client.embed(index.get(key).orElseThrow().description().searchText()));
                    done++;
                    inARow = 0;
                } catch (EmbedException e) {
                    failed++;
                    System.err.println("failed " + key + ": " + e.getMessage());
                    if (++inARow >= MAX_CONSECUTIVE_EMBED_FAILURES) {
                        System.err.println("stopping after " + inARow + " failures in a row");
                        break;
                    }
                }
            }
            System.out.println("embedded " + done + ", failed " + failed + ", left " + (keys.size() - done));
        }
    }

    private static SearchService.Result find(Config config, ClipIndex index, SearchArgs args) throws IOException {
        Catalog catalog = LibraryScanner.loadCatalog(config.clipbankDir());
        Map<String, Clip> clips = LibraryScanner.scan(config.clipbankDir(), catalog).stream()
                .collect(Collectors.toMap(Clip::key, Function.identity(), (a, b) -> a));
        Embedder embedder = config.ollamaUrl() == null ? null
                : new EmbedClient(config.requireOllamaUrl(), config.embedModel(),
                        index.embeddingDimension(config.embedModel()));
        var result = SearchService.search(index, embedder, config.embedModel(), args.query(), args.filter(),
                args.limit(), config.clipbankDir(), clips, catalog, args.minSimilarity());
        result.notes().forEach(n -> System.err.println("note: " + n));
        return result;
    }

    /** Writes the shortlist of a search to a file outside the library. */
    private static void export(Config config, ExportArgs args) throws IOException {
        requireClipbank(config);
        Exporter.checkTarget(args.out(), config.clipbankDir(), args.force());
        try (ClipIndex index = ClipIndex.open(config.dbPath())) {
            var result = find(config, index, args.search());
            Exporter.write(args.out(), config.clipbankDir(), args.force(), args.format(), args.search().query(),
                    result.hits());
            System.out.println("exported " + result.hits().size() + " result(s) to " + printable(args.out().toString()));
        }
    }

    private static void search(Config config, SearchArgs args) throws IOException {
        requireClipbank(config);
        try (ClipIndex index = ClipIndex.open(config.dbPath())) {
            var result = find(config, index, args);
            int rank = 1;
            for (var hit : result.hits()) {
                System.out.printf(Locale.ROOT, "%d. %s  score %.4f  sim %s  %s%n", rank++, printable(hit.key()),
                        hit.score(), hit.similarity() == null ? "-" : String.format(Locale.ROOT, "%.2f", hit.similarity()),
                        hit.duration() == null ? "?" : String.format(Locale.ROOT, "%.1fs", hit.duration()));
                System.out.println("   " + hit.description());
                System.out.println("   status: " + (hit.channelStatus().isEmpty() ? "-"
                        : new TreeMap<>(hit.channelStatus()).entrySet().stream()
                                .map(e -> printable(e.getKey()) + "=" + printable(e.getValue()))
                                .collect(Collectors.joining(" ")))
                        + " | used: " + (hit.usedOn().isEmpty() ? "-" : printable(String.join(",", hit.usedOn()))));
                System.out.println("   " + printable(hit.path().toString()));
            }
            if (result.hits().isEmpty()) {
                System.out.println("no results");
            }
        }
    }

    /** Catalog and file names are data: control characters never reach the terminal. */
    private static String printable(String s) {
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? '?' : cp));
        return out.toString();
    }

    /** Describes a single clip; mostly useful to try the model setup. */
    private static void describe(Config config, Path clip) {
        requireClipbank(config);
        var vision = new VisionClient(config.requireOllamaUrl(), config.visionModel());
        try (ClipFrames frames = new FrameExtractor(config.clipbankDir(), config.frameCount()).extract(clip)) {
            Description d = vision.describe(frames.frames());
            System.out.println("description: " + d.description());
            System.out.println("subjects: " + String.join(", ", d.subjects()));
            System.out.println("setting: " + d.setting());
            System.out.println("time_of_day: " + d.timeOfDay());
            System.out.println("mood: " + d.mood());
        } catch (IOException e) {
            fail("Error: " + e.getMessage());
        }
    }

    private static void requireClipbank(Config config) {
        if (config.clipbankDir() == null) {
            throw new IllegalArgumentException(Config.CLIPBANK_DIR + " is not set");
        }
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
