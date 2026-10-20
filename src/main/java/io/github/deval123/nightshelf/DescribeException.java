package io.github.deval123.nightshelf;

import java.io.IOException;

/** A clip could not be described. Only that clip is affected; the message is sanitized. */
public class DescribeException extends IOException {

    private final boolean retryable;

    DescribeException(String message, boolean retryable, Throwable cause) {
        super(Sanitize.clean(message, 500), cause);
        this.retryable = retryable;
    }

    boolean retryable() {
        return retryable;
    }
}
