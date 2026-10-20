package io.github.deval123.nightshelf;

import java.io.IOException;
import java.nio.file.Path;

/** A clip could not be turned into frames. Only that clip is affected. */
public class FrameException extends IOException {

    public FrameException(Path clip, String message) {
        super(clip + ": " + message);
    }

    public FrameException(Path clip, String message, Throwable cause) {
        super(clip + ": " + message, cause);
    }
}
