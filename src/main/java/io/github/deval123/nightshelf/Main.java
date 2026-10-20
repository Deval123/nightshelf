package io.github.deval123.nightshelf;

import java.io.IOException;
import java.nio.file.Path;

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
