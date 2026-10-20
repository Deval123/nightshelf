package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

    private static final Path HOME = Path.of("/home/test");

    @Test
    void usesDefaultsWhenEnvironmentIsEmpty() {
        Config config = Config.from(Map.of(), HOME);

        assertNull(config.clipbankDir());
        assertNull(config.ollamaUrl());
        assertEquals(HOME.resolve(".nightshelf").resolve("index.db"), config.dbPath());
        assertEquals(Config.DEFAULT_VISION_MODEL, config.visionModel());
        assertEquals(Config.DEFAULT_EMBED_MODEL, config.embedModel());
    }

    @Test
    void readsEveryVariable() {
        Config config = Config.from(Map.of(
                "CLIPBANK_DIR", "/clips",
                "DB_PATH", "/data/n.db",
                "OLLAMA_URL", "http://ollama.example:1234",
                "VISION_MODEL", "v",
                "EMBED_MODEL", "e"), HOME);

        assertEquals(Path.of("/clips"), config.clipbankDir());
        assertEquals(Path.of("/data/n.db"), config.dbPath());
        assertEquals("http://ollama.example:1234", config.ollamaUrl());
        assertEquals("v", config.visionModel());
        assertEquals("e", config.embedModel());
    }

    @Test
    void blankValuesCountAsUnset() {
        Config config = Config.from(Map.of("CLIPBANK_DIR", "  ", "VISION_MODEL", ""), HOME);

        assertNull(config.clipbankDir());
        assertEquals(Config.DEFAULT_VISION_MODEL, config.visionModel());
    }

    @Test
    void toStringMarksUnsetValues() {
        assertTrue(Config.from(Map.of(), HOME).toString().contains("CLIPBANK_DIR=<unset>"));
    }
}
