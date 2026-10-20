package io.github.deval123.nightshelf;

import java.util.ArrayList;
import java.util.List;

/** Command line of {@code search "<text>" [options]}. Problems are reported in one line. */
record SearchArgs(String query, SearchFilter filter, int limit) {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    static SearchArgs parse(List<String> args) {
        List<String> positional = new ArrayList<>();
        String channel = null;
        String status = null;
        String orientation = null;
        Double minDuration = null;
        int limit = DEFAULT_LIMIT;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!a.startsWith("--")) {
                positional.add(a);
                continue;
            }
            if (!List.of("--channel", "--status", "--orientation", "--min-duration", "--limit").contains(a)) {
                throw new IllegalArgumentException("unknown option " + Sanitize.clean(a, 40));
            }
            if (i + 1 >= args.size() || args.get(i + 1).startsWith("--")) {
                throw new IllegalArgumentException(a + " needs a value");
            }
            String value = args.get(++i);
            switch (a) {
                case "--channel" -> channel = value;
                case "--status" -> status = value;
                case "--orientation" -> orientation = value;
                case "--min-duration" -> minDuration = number(a, value);
                default -> limit = limit(value);
            }
        }
        if (positional.size() != 1 || positional.get(0).isBlank()) {
            throw new IllegalArgumentException("usage: search \"<text>\" [--channel X] [--status S]"
                    + " [--orientation O] [--min-duration SECONDS] [--limit N]");
        }
        SearchFilter filter = new SearchFilter(channel,
                status == null ? SearchFilter.Status.DEFAULT : SearchFilter.Status.parse(status),
                orientation, minDuration);
        return new SearchArgs(positional.get(0), filter, limit);
    }

    private static double number(String option, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " must be a number: " + Sanitize.clean(value, 40));
        }
    }

    private static int limit(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n >= 1 && n <= MAX_LIMIT) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException("--limit must be an integer from 1 to " + MAX_LIMIT + ": "
                + Sanitize.clean(value, 40));
    }
}
