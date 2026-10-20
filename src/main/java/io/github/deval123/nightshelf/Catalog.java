package io.github.deval123.nightshelf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Integer uses) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Raw(Integer version, List<Entry> clips) {
    }

    public static Catalog empty() {
        return new Catalog(List.of());
    }

    public static Catalog read(Path file) throws IOException {
        Raw raw = new ObjectMapper().readValue(Files.readAllBytes(file), Raw.class);
        if (raw.version() == null || raw.version() != SUPPORTED_VERSION) {
            throw new IOException("Unsupported catalog version " + raw.version()
                    + " in " + file + " (expected " + SUPPORTED_VERSION + ")");
        }
        return new Catalog(raw.clips() == null ? List.of() : List.copyOf(raw.clips()));
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
