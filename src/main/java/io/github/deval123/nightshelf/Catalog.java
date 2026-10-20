package io.github.deval123.nightshelf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of {@code catalog.json}. This class never writes the file.
 */
public record Catalog(List<Entry> clips) {

    static final int SUPPORTED_VERSION = 1;

    /** Per-clip metadata; fields we do not use are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            String source,
            String id,
            String file,
            Map<String, String> status,
            List<Use> uses) {
    }

    /** One use of a clip; its fields are not needed, only the count. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Use() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Raw(Integer version, JsonNode clips) {
    }

    public static Catalog empty() {
        return new Catalog(List.of());
    }

    /**
     * Expects {@code {"version": 1, "clips": {"<source>:<id>": {...}, ...}}}.
     * The keys are ignored; only the values are used.
     */
    public static Catalog read(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Raw raw = mapper.readValue(Files.readAllBytes(file), Raw.class);
        if (raw.version() == null || raw.version() != SUPPORTED_VERSION) {
            throw new IOException("Unsupported catalog version " + raw.version()
                    + " in " + file + " (expected " + SUPPORTED_VERSION + ")");
        }
        if (raw.clips() == null || !raw.clips().isObject()) {
            throw new IOException("Unexpected catalog format in " + file
                    + ": \"clips\" must be an object keyed by \"<source>:<id>\","
                    + " e.g. {\"version\": 1, \"clips\": {\"pixabay:111\": {...}}}");
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : raw.clips()) {
            entries.add(mapper.treeToValue(node, Entry.class));
        }
        return new Catalog(List.copyOf(entries));
    }

    /** Finds the entry for a file name within a source, or null. */
    Entry find(String source, String fileName) {
        for (Entry e : clips) {
            if (source.equals(e.source()) && e.file() != null
                    && fileName.equals(Path.of(e.file()).getFileName().toString())) {
                return e;
            }
        }
        return null;
    }
}
