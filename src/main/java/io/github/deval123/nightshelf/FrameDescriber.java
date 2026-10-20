package io.github.deval123.nightshelf;

import java.io.IOException;

/** Extracts the frames of a clip, describes them, and always deletes the frames afterwards. */
public final class FrameDescriber implements ClipDescriber {

    private final FrameExtractor extractor;
    private final VisionClient vision;

    public FrameDescriber(FrameExtractor extractor, VisionClient vision) {
        this.extractor = extractor;
        this.vision = vision;
    }

    @Override
    public Description describe(Clip clip) throws IOException {
        try (ClipFrames frames = extractor.extract(clip.path())) {
            return vision.describe(frames.frames());
        }
    }
}
