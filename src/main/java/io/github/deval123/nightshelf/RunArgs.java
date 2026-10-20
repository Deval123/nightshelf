package io.github.deval123.nightshelf;

import java.time.Duration;
import java.util.List;

/** Command line of {@code run}. Problems are reported in one line. */
record RunArgs(Integer maxClips, Duration timeBudget, boolean skipEmbed, boolean retryFailed,
        boolean allowMassRemoval) {

    static RunArgs parse(List<String> args) {
        Integer maxClips = null;
        Duration budget = null;
        boolean skipEmbed = false;
        boolean retryFailed = false;
        boolean allowMassRemoval = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--skip-embed" -> skipEmbed = true;
                case "--retry-failed" -> retryFailed = true;
                case "--allow-mass-removal" -> allowMassRemoval = true;
                case "--max-clips", "--time-budget" -> {
                    if (i + 1 >= args.size() || args.get(i + 1).startsWith("--")) {
                        throw new IllegalArgumentException(a + " needs a value");
                    }
                    int n = positive(a, args.get(++i));
                    if (a.equals("--max-clips")) {
                        maxClips = n;
                    } else {
                        budget = Duration.ofMinutes(n);
                    }
                }
                default -> throw new IllegalArgumentException("unknown argument " + Sanitize.clean(a, 40)
                        + " (run accepts --max-clips N, --time-budget MINUTES, --skip-embed, --retry-failed,"
                        + " --allow-mass-removal)");
            }
        }
        return new RunArgs(maxClips, budget, skipEmbed, retryFailed, allowMassRemoval);
    }

    private static int positive(String option, String value) {
        try {
            int n = Integer.parseInt(value);
            if (n >= 1 && n <= 1_000_000) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException(option + " must be a whole number of at least 1: "
                + Sanitize.clean(value, 40));
    }
}
