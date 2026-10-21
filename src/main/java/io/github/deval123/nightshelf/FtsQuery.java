package io.github.deval123.nightshelf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns user text into a safe FTS5 MATCH expression. The text is split into
 * letter and digit words and every word is quoted, so operators, prefixes,
 * quotes and column filters in the input are plain text and never syntax.
 * Common English function words and the framing words that nearly every
 * description repeats are dropped, because they match everything; if that
 * leaves nothing, the original words are used so the query is never empty.
 */
final class FtsQuery {

    static final int MAX_TOKENS = 32;
    static final int MAX_TOKEN_LENGTH = 64;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}\\p{M}]+");

    /** Words that carry no meaning for a search, plus the framing the descriptions repeat. */
    static final Set<String> IGNORED = Set.of(
            "in", "on", "at", "the", "a", "an", "of", "and", "or", "to", "for", "with", "by", "from", "is", "are",
            "as", "it", "its", "this", "that", "into", "over", "near",
            "video", "clip", "shows", "shown", "captures", "depicts", "features", "scene");

    private FtsQuery() {
    }

    /** @return the expression (words ORed together), or null if the text has no words */
    static String match(String text) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find() && words.size() < MAX_TOKENS) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (w.length() > MAX_TOKEN_LENGTH) {
                w = w.substring(0, MAX_TOKEN_LENGTH);
            }
            words.add(w);
        }
        List<String> kept = words.stream().filter(w -> !IGNORED.contains(w)).toList();
        List<String> used = kept.isEmpty() ? words : kept;
        return used.isEmpty() ? null
                : used.stream().map(w -> "\"" + w + "\"").collect(Collectors.joining(" OR "));
    }
}
