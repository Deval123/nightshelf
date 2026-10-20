package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangeDetectorTest {

    private static Clip clip(String id, long size, long mtime) {
        return new Clip("s", id, Path.of(id + ".mp4"), size, mtime, true, Map.of(), 0);
    }

    @Test
    void classifiesAddedChangedUnchangedAndRemoved() {
        Map<String, ChangeDetector.Known> known = Map.of(
                "s/same", new ChangeDetector.Known(10, 100),
                "s/resized", new ChangeDetector.Known(10, 100),
                "s/touched", new ChangeDetector.Known(10, 100),
                "s/gone", new ChangeDetector.Known(10, 100));

        var changes = ChangeDetector.detect(known, List.of(
                clip("same", 10, 100),
                clip("resized", 11, 100),
                clip("touched", 10, 2_100),
                clip("new", 5, 5)));

        assertEquals(List.of("s/new"), changes.added().stream().map(Clip::key).toList());
        assertEquals(List.of("s/resized", "s/touched"), changes.changed().stream().map(Clip::key).toList());
        assertEquals(List.of("s/same"), changes.unchanged().stream().map(Clip::key).toList());
        assertEquals(List.of("s/gone"), changes.removed());
    }

    @Test
    void ignoresSubSecondMtimeDifferences() {
        var known = Map.of("s/a", new ChangeDetector.Known(10, 5_000));

        var same = ChangeDetector.detect(known, List.of(clip("a", 10, 5_999)));
        var later = ChangeDetector.detect(known, List.of(clip("a", 10, 6_000)));

        assertEquals(1, same.unchanged().size());
        assertEquals(1, later.changed().size());
    }
}
