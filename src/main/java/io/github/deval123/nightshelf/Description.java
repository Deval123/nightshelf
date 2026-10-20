package io.github.deval123.nightshelf;

import java.util.List;

/** What the vision model said about one clip, already validated and sanitized. */
public record Description(
        String description,
        List<String> subjects,
        String setting,
        String timeOfDay,
        String mood) {

    static final int MAX_DESCRIPTION = 400;
    static final int MAX_SUBJECTS = 8;
    static final int MAX_SUBJECT = 80;
    static final int MAX_FIELD = 120;
    static final List<String> TIMES_OF_DAY = List.of("day", "night", "dusk", "dawn", "unknown");

    /** The text that is indexed and embedded: all fields, plain, in one string. */
    public String searchText() {
        return String.join(" ", description, String.join(" ", subjects), setting, mood, timeOfDay).strip();
    }
}
