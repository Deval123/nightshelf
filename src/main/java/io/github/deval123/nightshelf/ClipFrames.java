package io.github.deval123.nightshelf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Frames extracted for one clip. Closing deletes the temporary directory. */
public record ClipFrames(Path dir, List<Path> frames) implements AutoCloseable {

    @Override
    public void close() {
        deleteQuietly(dir);
    }

    static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // Best effort: a leftover temp directory must not fail the run.
        }
    }
}
