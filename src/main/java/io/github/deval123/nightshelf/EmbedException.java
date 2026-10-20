package io.github.deval123.nightshelf;

import java.io.IOException;

/** A text could not be embedded. Only that text is affected; the message is sanitized. */
public class EmbedException extends IOException {

    private final boolean retryable;

    EmbedException(String message, boolean retryable, Throwable cause) {
        super(Sanitize.clean(message, 500), cause);
        this.retryable = retryable;
    }

    boolean retryable() {
        return retryable;
    }
}
