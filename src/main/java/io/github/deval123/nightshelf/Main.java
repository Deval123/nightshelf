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

    public static void main(String[] args) throws IOException {
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
            } else if (args.length > 0 && args[0].equals("embed")) {
                embed(config);
            } else if (args.length > 0 && args[0].equals("search")) {
                search(config, SearchArgs.parse(List.of(args).subList(1, args.length)));
            } else if (args.length == 2 && args[0].equals("describe")) {
                describe(config, Path.of(args[1]));
            } else {
                System.out.println(config);
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

    private static void search(Config config, SearchArgs args) throws IOException {
        requireClipbank(config);
        Catalog catalog = LibraryScanner.loadCatalog(config.clipbankDir());
        Map<String, Clip> clips = LibraryScanner.scan(config.clipbankDir(), catalog).stream()
                .collect(Collectors.toMap(Clip::key, Function.identity(), (a, b) -> a));
        try (ClipIndex index = ClipIndex.open(config.dbPath())) {
            Embedder embedder = config.ollamaUrl() == null ? null
                    : new EmbedClient(config.requireOllamaUrl(), config.embedModel(),
                            index.embeddingDimension(config.embedModel()));
            var result = SearchService.search(index, embedder, config.embedModel(), args.query(), args.filter(),
                    args.limit(), config.clipbankDir(), clips, catalog);
            result.notes().forEach(n -> System.err.println("note: " + n));
            int rank = 1;
            for (var hit : result.hits()) {
                System.out.printf(Locale.ROOT, "%d. %s  score %.4f  %s%n", rank++, printable(hit.key()), hit.score(),
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
