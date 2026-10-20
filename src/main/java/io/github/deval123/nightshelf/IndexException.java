package io.github.deval123.nightshelf;

/** The index cannot be used (unsupported storage, unknown schema, database error). */
public class IndexException extends RuntimeException {

    public IndexException(String message) {
        super(message);
    }

    public IndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
