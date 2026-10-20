package io.github.deval123.nightshelf;

import java.io.IOException;
import java.nio.file.Path;

/** Path checks shared by everything that hands a clip path to an external tool or to the user. */
final class ClipPaths {

    private ClipPaths() {
    }

    /**
     * Rejects option-like names and anything whose real path is outside the library.
     *
     * @return the real path of the clip
     */
    static Path requireInside(Path clipbankDir, Path clip) throws FrameException {
        Path name = clip.getFileName();
        if (clip.toString().startsWith("-") || name == null || name.toString().startsWith("-")) {
            throw new FrameException(clip, "path must not start with '-'");
        }
        try {
            Path real = clip.toRealPath();
            if (!real.startsWith(clipbankDir.toRealPath())) {
                throw new FrameException(clip, "path is outside the clip library");
            }
            return real;
        } catch (IOException e) {
            if (e instanceof FrameException f) {
                throw f;
            }
            throw new FrameException(clip, "cannot read clip", e);
        }
    }
}
