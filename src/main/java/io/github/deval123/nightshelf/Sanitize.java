package io.github.deval123.nightshelf;

/**
 * Cleans text that came from a model (and so, indirectly, from an image)
 * before it is stored or printed. Control characters, including ESC and the
 * C1 range, are removed, so the text cannot carry terminal escape sequences.
 */
final class Sanitize {

    private Sanitize() {
    }

    /** Drops unsafe characters, collapses whitespace and caps the length in code points. */
    static String clean(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean pendingSpace = false;
        int count = 0;
        for (int i = 0; i < text.length() && count < maxCodePoints; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR
                    || cp == '\n' || cp == '\r' || cp == '\t') {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (!isSafe(cp)) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                count++;
                pendingSpace = false;
                if (count >= maxCodePoints) {
                    break;
                }
            }
            out.appendCodePoint(cp);
            count++;
        }
        return out.toString();
    }

    private static boolean isSafe(int cp) {
        if (Character.isISOControl(cp)) {
            return false;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE,
                    Character.PRIVATE_USE, Character.UNASSIGNED,
                    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        };
    }
}
