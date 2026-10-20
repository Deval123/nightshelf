package io.github.deval123.nightshelf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunArgsTest {

    @Test
    void parsesEveryOption() {
        RunArgs a = RunArgs.parse(List.of("--max-clips", "20", "--time-budget", "90", "--skip-embed",
                "--retry-failed", "--allow-mass-removal"));

        assertEquals(20, a.maxClips());
        assertEquals(Duration.ofMinutes(90), a.timeBudget());
        assertTrue(a.skipEmbed() && a.retryFailed() && a.allowMassRemoval());
    }

    @Test
    void defaultsAreUnlimitedAndSafe() {
        RunArgs a = RunArgs.parse(List.of());

        assertNull(a.maxClips());
        assertNull(a.timeBudget());
        assertFalse(a.skipEmbed() || a.retryFailed() || a.allowMassRemoval());
    }

    @Test
    void rejectsBadArgumentsWithOneLine() {
        for (List<String> bad : List.of(
                List.of("--max-clips"),
                List.of("--max-clips", "0"),
                List.of("--max-clips", "x"),
                List.of("--time-budget", "-5"),
                List.of("--time-budget", "--skip-embed"),
                List.of("--limit", "3"),
                List.of("extra"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RunArgs.parse(bad),
                    bad.toString());
            assertFalse(e.getMessage().contains("\n"), e.getMessage());
        }
    }
}
