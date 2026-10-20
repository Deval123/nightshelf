package io.github.deval123.nightshelf;

import java.nio.file.Path;
import java.util.Map;

/**
 * A clip found on disk, joined with its catalog metadata when there is some.
 *
 * @param catalogued false when the catalog has no entry; {@code id} is then
 *                   the file name without extension
 */
public record Clip(
        String source,
        String id,
        Path path,
        long size,
        long mtimeMillis,
        boolean catalogued,
        Map<String, String> status,
        int uses) {

    /** Stable key across scans. */
    public String key() {
        return source + "/" + id;
    }
}
