package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchFilterTest {

    private static Catalog.Entry entry(Map<String, String> status, List<Catalog.Use> uses) {
        return new Catalog.Entry("src", "a", "clips/src/a.mp4", status, uses, 10.0, "portrait");
    }

    private static SearchFilter filter(SearchFilter.Status status) {
        return new SearchFilter("chan", status, null, null);
    }

    @Test
    void unusedExcludesClipsRejectedForTheChannel() {
        Catalog.Entry rejectedNeverUsed = entry(Map.of("chan", "rejected"), List.of());

        assertFalse(filter(SearchFilter.Status.UNUSED).matches(rejectedNeverUsed));
        assertTrue(filter(SearchFilter.Status.REJECTED).matches(rejectedNeverUsed));
        assertFalse(filter(SearchFilter.Status.DEFAULT).matches(rejectedNeverUsed));
        assertTrue(filter(SearchFilter.Status.ANY).matches(rejectedNeverUsed));
    }

    @Test
    void unusedKeepsNeverUsedClipsThatAreNotRejected() {
        assertTrue(filter(SearchFilter.Status.UNUSED).matches(entry(Map.of(), List.of())));
        assertTrue(filter(SearchFilter.Status.UNUSED).matches(entry(Map.of("chan", "approved"), List.of())));
        assertTrue(filter(SearchFilter.Status.UNUSED).matches(entry(Map.of("other", "rejected"), List.of())));
        assertTrue(filter(SearchFilter.Status.UNUSED).matches(null));
    }

    @Test
    void unusedExcludesClipsUsedOnTheChannel() {
        Catalog.Entry used = entry(Map.of(), List.of(new Catalog.Use("chan")));

        assertFalse(filter(SearchFilter.Status.UNUSED).matches(used));
        assertTrue(new SearchFilter("other", SearchFilter.Status.UNUSED, null, null).matches(used));
    }

    @Test
    void theDefaultStillHidesOnlyRejectedClips() {
        assertTrue(filter(SearchFilter.Status.DEFAULT).matches(entry(Map.of(), List.of(new Catalog.Use("chan")))));
        assertTrue(filter(SearchFilter.Status.DEFAULT).matches(entry(Map.of("chan", "approved"), List.of())));
        assertFalse(filter(SearchFilter.Status.DEFAULT).matches(entry(Map.of("chan", "rejected"), List.of())));
    }
}
