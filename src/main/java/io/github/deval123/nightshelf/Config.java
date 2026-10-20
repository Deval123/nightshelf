package io.github.deval123.nightshelf;

import java.nio.file.Path;
import java.util.Map;

/**
 * Runtime configuration, read from environment variables.
 *
 * <p>{@code clipbankDir} and {@code ollamaUrl} have no default: they point at
 * machine-specific locations that must never be baked into the code.
 */
public record Config(
        Path clipbankDir,
        Path dbPath,
        String ollamaUrl,
        String visionModel,
        String embedModel,
        int frameCount) {

    static final String CLIPBANK_DIR = "CLIPBANK_DIR";
    static final String DB_PATH = "DB_PATH";
    static final String OLLAMA_URL = "OLLAMA_URL";
    static final String VISION_MODEL = "VISION_MODEL";
    static final String EMBED_MODEL = "EMBED_MODEL";
    static final String FRAME_COUNT = "FRAME_COUNT";

    static final String DEFAULT_VISION_MODEL = "gemma3:4b";
    static final String DEFAULT_EMBED_MODEL = "nomic-embed-text";
    static final int DEFAULT_FRAME_COUNT = 3;

    public static Config fromEnv() {
        return from(System.getenv(), Path.of(System.getProperty("user.home")));
    }

    static Config from(Map<String, String> env, Path home) {
        return new Config(
                path(env.get(CLIPBANK_DIR)),
                path(env.get(DB_PATH)) != null
                        ? path(env.get(DB_PATH))
                        : home.resolve(".nightshelf").resolve("index.db"),
                blankToNull(env.get(OLLAMA_URL)),
                orDefault(env.get(VISION_MODEL), DEFAULT_VISION_MODEL),
                orDefault(env.get(EMBED_MODEL), DEFAULT_EMBED_MODEL),
                frameCount(env.get(FRAME_COUNT)));
    }

    private static int frameCount(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return DEFAULT_FRAME_COUNT;
        }
        try {
            int n = Integer.parseInt(v);
            if (n >= 1) {
                return n;
            }
        } catch (NumberFormatException e) {
            // fall through to the error below
        }
        throw new IllegalArgumentException(FRAME_COUNT + " must be a positive integer: " + v);
    }

    private static Path path(String value) {
        String v = blankToNull(value);
        return v == null ? null : Path.of(v);
    }

    private static String orDefault(String value, String fallback) {
        String v = blankToNull(value);
        return v == null ? fallback : v;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public String toString() {
        return String.join("\n",
                CLIPBANK_DIR + "=" + show(clipbankDir),
                DB_PATH + "=" + show(dbPath),
                OLLAMA_URL + "=" + show(ollamaUrl),
                VISION_MODEL + "=" + visionModel,
                EMBED_MODEL + "=" + embedModel,
                FRAME_COUNT + "=" + frameCount);
    }

    private static String show(Object value) {
        return value == null ? "<unset>" : value.toString();
    }
}
