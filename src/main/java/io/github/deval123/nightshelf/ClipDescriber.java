package io.github.deval123.nightshelf;

import java.io.IOException;

/** Produces the description of one clip. The real one extracts frames and asks the vision model. */
@FunctionalInterface
public interface ClipDescriber {

    Description describe(Clip clip) throws IOException;
}
