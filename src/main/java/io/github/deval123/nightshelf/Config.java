package io.github.deval123.nightshelf;

import java.net.URI;
import java.net.URISyntaxException;
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
    static final int MAX_FRAME_COUNT = 12;

    public static Config fromEnv() {
        return from(System.getenv(), Path.of(System.getProperty("user.home")));
    }

    static Config from(Map<String, String> env, Path home) {
        return new Config(
                path(env.get(CLIPBANK_DIR)),
                path(env.get(DB_PATH)) != null
                        ? path(env.get(DB_PATH))
                        : home.resolve(".nightshelf").resolve("index.db"),
                ollamaUrl(env.get(OLLAMA_URL)),
                orDefault(env.get(VISION_MODEL), DEFAULT_VISION_MODEL),
                orDefault(env.get(EMBED_MODEL), DEFAULT_EMBED_MODEL),
                frameCount(env.get(FRAME_COUNT)));
    }

    private static String ollamaUrl(String value) {
        String v = blankToNull(value);
        if (v != null) {
            parseOllamaUrl(v);
        }
        return v;
    }

    private static URI parseOllamaUrl(String v) {
        URI uri;
        try {
            uri = new URI(v);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(OLLAMA_URL + " is not a valid URL: " + v);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(OLLAMA_URL + " must start with http:// or https://: " + v);
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException(OLLAMA_URL + " has no host: " + v);
        }
        return uri;
    }

    /** The Ollama base URL, for commands that talk to Ollama. */
    public URI requireOllamaUrl() {
        if (ollamaUrl == null) {
            throw new IllegalArgumentException(OLLAMA_URL + " is not set (it must point at the Ollama API,"
                    + " e.g. http://localhost:11434)");
        }
        return parseOllamaUrl(ollamaUrl);
    }

    private static int frameCount(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return DEFAULT_FRAME_COUNT;
        }
        try {
            int n = Integer.parseInt(v);
            if (n >= 1 && n <= MAX_FRAME_COUNT) {
                return n;
            }
        } catch (NumberFormatException e) {
            // fall through to the error below
        }
        throw new IllegalArgumentException(FRAME_COUNT + " must be an integer from 1 to " + MAX_FRAME_COUNT + ": " + v);
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
