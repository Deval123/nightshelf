package io.github.deval123.nightshelf;

/** Counts over the whole index; clips flagged removed are counted apart. */
public record IndexTotals(int present, int pending, int described, int failed, int givenUp, int embedded, int removed) {
}
