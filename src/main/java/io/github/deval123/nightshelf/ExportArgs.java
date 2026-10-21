package io.github.deval123.nightshelf;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Command line of {@code export "<text>" [search options] --format md|csv|json --out FILE [--force]}. */
record ExportArgs(SearchArgs search, Exporter.Format format, Path out, boolean force) {

    static final String USAGE = "usage: export \"<text>\" --format md|csv|json --out <file> [--force] [--channel X]"
            + " [--status S] [--orientation O] [--min-duration SECONDS] [--min-similarity 0..1] [--limit N]";

    static ExportArgs parse(List<String> args) {
        List<String> rest = new ArrayList<>();
        String format = null;
        String out = null;
        boolean force = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--force" -> force = true;
                case "--format", "--out" -> {
                    if (i + 1 >= args.size() || args.get(i + 1).startsWith("--")) {
                        throw new IllegalArgumentException(a + " needs a value");
                    }
                    if (a.equals("--format")) {
                        format = args.get(++i);
                    } else {
                        out = args.get(++i);
                    }
                }
                default -> rest.add(a);
            }
        }
        if (format == null || out == null) {
            throw new IllegalArgumentException(USAGE);
        }
        Exporter.Format f;
        switch (format.toLowerCase(Locale.ROOT)) {
            case "md" -> f = Exporter.Format.MD;
            case "csv" -> f = Exporter.Format.CSV;
            case "json" -> f = Exporter.Format.JSON;
            default -> throw new IllegalArgumentException("--format must be md, csv or json: " + Sanitize.clean(format, 40));
        }
        if (out.isBlank() || out.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("--out must be a file name");
        }
        Path path;
        try {
            path = Path.of(out);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("--out is not a valid path: " + Sanitize.clean(out, 80));
        }
        SearchArgs search;
        try {
            search = SearchArgs.parse(rest);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().startsWith("usage:")) {
                throw new IllegalArgumentException(USAGE);
            }
            throw e;
        }
        return new ExportArgs(search, f, path, force);
    }
}
