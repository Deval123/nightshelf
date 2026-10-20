package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SanitizeTest {

    @Test
    void shortTextIsKeptAsIs() {
        assertEquals("one two", Sanitize.cleanAtWord("one  two", 20));
    }

    @Test
    void cutsAtTheLastWholeWord() {
        assertEquals("alpha beta\u2026", Sanitize.cleanAtWord("alpha beta gamma delta", 15));
    }

    @Test
    void keepsAWordThatEndsExactlyAtTheCap() {
        // "alpha beta" is 10 code points; the cap leaves room for the ellipsis after it.
        assertEquals("alpha beta\u2026", Sanitize.cleanAtWord("alpha beta gamma", 11));
    }

    @Test
    void dropsTrailingPunctuationBeforeTheEllipsis() {
        assertEquals("alpha, beta\u2026", Sanitize.cleanAtWord("alpha, beta, gamma", 14));
    }

    @Test
    void resultNeverExceedsTheCap() {
        String text = "lorem ipsum dolor sit amet ".repeat(30);
        for (int cap = 2; cap < 60; cap++) {
            String out = Sanitize.cleanAtWord(text, cap);
            org.junit.jupiter.api.Assertions.assertTrue(out.codePointCount(0, out.length()) <= cap, out);
        }
    }

    @Test
    void aSingleOverLongWordIsCutWhereItMustBe() {
        assertEquals("aaaa\u2026", Sanitize.cleanAtWord("aaaaaaaaaa", 5));
    }
}
