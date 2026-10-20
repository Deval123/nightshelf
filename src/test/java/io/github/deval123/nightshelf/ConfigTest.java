package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(3, config.frameCount());
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

    @Test
    void readsFrameCountAndRejectsInvalidValues() {
        assertEquals(5, Config.from(Map.of("FRAME_COUNT", "5"), HOME).frameCount());
        assertThrows(IllegalArgumentException.class, () -> Config.from(Map.of("FRAME_COUNT", "0"), HOME));
        assertThrows(IllegalArgumentException.class, () -> Config.from(Map.of("FRAME_COUNT", "x"), HOME));
    }

    @Test
    void capsFrameCountAtTwelve() {
        assertEquals(12, Config.from(Map.of("FRAME_COUNT", "12"), HOME).frameCount());
        assertThrows(IllegalArgumentException.class, () -> Config.from(Map.of("FRAME_COUNT", "13"), HOME));
    }

    @Test
    void ollamaUrlMustBeHttpOrHttps() {
        assertEquals("https://h:1", Config.from(Map.of("OLLAMA_URL", "https://h:1"), HOME).ollamaUrl());
        for (String bad : new String[] {"file:///etc/passwd", "ftp://h", "localhost:11434", "http://", "not a url"}) {
            assertThrows(IllegalArgumentException.class, () -> Config.from(Map.of("OLLAMA_URL", bad), HOME), bad);
        }
    }

    @Test
    void requireOllamaUrlExplainsWhenUnset() {
        var e = assertThrows(IllegalArgumentException.class, () -> Config.from(Map.of(), HOME).requireOllamaUrl());

        assertTrue(e.getMessage().contains("OLLAMA_URL is not set"), e.getMessage());
        assertEquals("http://h:11434",
                Config.from(Map.of("OLLAMA_URL", "http://h:11434"), HOME).requireOllamaUrl().toString());
    }
}
