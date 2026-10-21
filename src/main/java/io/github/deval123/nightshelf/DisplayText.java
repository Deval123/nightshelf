package io.github.deval123.nightshelf;

import java.util.regex.Pattern;

/** Changes how a stored description is shown. The stored text and the embeddings are never touched. */
final class DisplayText {

    private static final Pattern FRAMING = Pattern.compile(
            "^\\s*(?:the|this|a)\\s+(?:video|clip)\\s+(?:of|shows?|captures?|depicts?|features?|presents?)\\s+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private DisplayText() {
    }

    /** Drops a leading "The video shows ", "A clip of " and the like, then capitalises the first letter. */
    static String withoutFraming(String description) {
        if (description == null) {
            return null;
        }
        var m = FRAMING.matcher(description);
        if (!m.find() || m.end() >= description.length()) {
            return description;
        }
        String rest = description.substring(m.end());
        int first = rest.codePointAt(0);
        return new StringBuilder().appendCodePoint(Character.toUpperCase(first))
                .append(rest, Character.charCount(first), rest.length()).toString();
    }
}
