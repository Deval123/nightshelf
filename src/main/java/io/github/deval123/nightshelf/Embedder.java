package io.github.deval123.nightshelf;

/** Turns text into a vector. Implemented by {@link EmbedClient}; tests use fakes. */
@FunctionalInterface
public interface Embedder {

    float[] embed(String text) throws EmbedException;
}
