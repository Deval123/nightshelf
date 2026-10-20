package io.github.deval123.nightshelf;

import java.io.IOException;

/** A clip could not be described. Only that clip is affected; the message is sanitized. */
public class DescribeException extends IOException {

    private final boolean retryable;
    private final boolean malformedOutput;

    DescribeException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, false, cause);
    }

    DescribeException(String message, boolean retryable, boolean malformedOutput, Throwable cause) {
        super(Sanitize.clean(message, 500), cause);
        this.retryable = retryable;
        this.malformedOutput = malformedOutput;
    }

    /** True when the model answered but the answer was unusable. */
    boolean malformedOutput() {
        return malformedOutput;
    }

    boolean retryable() {
        return retryable;
    }
}
