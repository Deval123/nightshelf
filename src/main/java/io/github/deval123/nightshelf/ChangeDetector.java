package io.github.deval123.nightshelf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compares a fresh scan with the previously known state of the library. */
public final class ChangeDetector {

    /** What was recorded for a clip the last time it was indexed. */
    public record Known(long size, long mtimeMillis) {
    }

    public record Changes(List<Clip> added, List<Clip> changed, List<Clip> unchanged, List<String> removed) {
    }

    private ChangeDetector() {
    }

    /** @param known previous state keyed by {@link Clip#key()} */
    public static Changes detect(Map<String, Known> known, List<Clip> scanned) {
        List<Clip> added = new ArrayList<>();
        List<Clip> changed = new ArrayList<>();
        List<Clip> unchanged = new ArrayList<>();
        List<String> removed = new ArrayList<>(known.keySet());

        for (Clip clip : scanned) {
            removed.remove(clip.key());
            Known old = known.get(clip.key());
            if (old == null) {
                added.add(clip);
            } else if (old.size() != clip.size() || old.mtimeMillis() != clip.mtimeMillis()) {
                changed.add(clip);
            } else {
                unchanged.add(clip);
            }
        }
        removed.sort(null);
        return new Changes(added, changed, unchanged, removed);
    }
}
