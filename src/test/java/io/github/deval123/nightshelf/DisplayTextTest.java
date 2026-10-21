package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class DisplayTextTest {

    @Test
    void leadingFramingIsRemovedAndTheFirstLetterCapitalised() {
        assertEquals("A dog runs.", DisplayText.withoutFraming("The video shows a dog runs."));
        assertEquals("Waves at dusk", DisplayText.withoutFraming("The video captures waves at dusk"));
        assertEquals("Two cars", DisplayText.withoutFraming("The video depicts two cars"));
        assertEquals("Lamps", DisplayText.withoutFraming("The video features lamps"));
        assertEquals("Fog over a field", DisplayText.withoutFraming("The video presents fog over a field"));
        assertEquals("Snow falls", DisplayText.withoutFraming("This video shows snow falls"));
        assertEquals("Birds", DisplayText.withoutFraming("The clip captures birds"));
        assertEquals("A harbour", DisplayText.withoutFraming("A video of a harbour"));
        assertEquals("Trees", DisplayText.withoutFraming("a clip of trees"));
        assertEquals("Trees", DisplayText.withoutFraming("  THE VIDEO SHOWS trees"));
    }

    @Test
    void otherTextIsLeftAlone() {
        assertEquals("A dog on a video wall", DisplayText.withoutFraming("A dog on a video wall"));
        assertEquals("Videos show dogs", DisplayText.withoutFraming("Videos show dogs"));
        assertEquals("The videographer shows a dog", DisplayText.withoutFraming("The videographer shows a dog"));
        assertEquals("", DisplayText.withoutFraming(""));
        assertNull(DisplayText.withoutFraming(null));
    }

    @Test
    void onlyOneLeadingPhraseIsRemoved() {
        assertEquals("The video shows a dog", DisplayText.withoutFraming("The video shows the video shows a dog"));
    }

    @Test
    void aDescriptionThatIsOnlyFramingIsKept() {
        assertEquals("The video shows ", DisplayText.withoutFraming("The video shows "));
    }

    @Test
    void nonAsciiFirstLettersAreCapitalised() {
        assertEquals("Élan du matin", DisplayText.withoutFraming("The clip shows élan du matin"));
    }
}
