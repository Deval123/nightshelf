package io.github.deval123.nightshelf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns user text into a safe FTS5 MATCH expression. The text is split into
 * letter and digit words and every word is quoted, so operators, prefixes,
 * quotes and column filters in the input are plain text and never syntax.
 */
final class FtsQuery {

    static final int MAX_TOKENS = 32;
    static final int MAX_TOKEN_LENGTH = 64;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}\\p{M}]+");

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
            words.add("\"" + w + "\"");
        }
        return words.isEmpty() ? null : String.join(" OR ", words);
    }
}
