package io.github.deval123.nightshelf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Writes the shortlist of a search to a file. Nothing is copied or moved: only
 * keys, paths and text go into the file. The target is never inside the clip
 * library, is never an existing file unless asked, and a symbolic link at the
 * target path is never followed.
 */
final class Exporter {

    enum Format { MD, CSV, JSON }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private Exporter() {
    }

    /**
     * Checks the target without creating it.
     *
     * @return the absolute target: the real path of its directory plus the file name
     */
    static Path checkTarget(Path out, Path clipbankDir, boolean force) throws IOException {
        Path abs = out.toAbsolutePath().normalize();
        Path name = abs.getFileName();
        Path parent = abs.getParent();
        if (name == null || parent == null) {
            throw new IllegalArgumentException("--out must be a file name");
        }
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("the directory of --out does not exist");
        }
        Path target = parent.toRealPath().resolve(name);
        if (target.startsWith(clipbankDir.toRealPath())) {
            throw new IllegalArgumentException("--out must not be inside the clip library (it is read-only)");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target)) {
                throw new IllegalArgumentException("--out is a symbolic link; refusing to follow it");
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("--out exists and is not a regular file");
            }
            if (!force) {
                throw new IllegalArgumentException("--out already exists; use --force to overwrite it");
            }
        }
        return target;
    }

    /** Renders and writes the file; the content only becomes visible at the target once it is complete. */
    static void write(Path out, Path clipbankDir, boolean force, Format format, String query,
            List<SearchService.Hit> hits) throws IOException {
        Path target = checkTarget(out, clipbankDir, force);
        byte[] content = render(format, query, hits).getBytes(StandardCharsets.UTF_8);
        Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp" + Long.toHexString(RANDOM.nextLong()));
        try {
            try (OutputStream os = Channels.newOutputStream(Files.newByteChannel(tmp,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    ownerOnly()))) {
                os.write(content);
            }
            if (force) {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                try {
                    Files.move(tmp, target);
                } catch (FileAlreadyExistsException e) {
                    throw new IllegalArgumentException("--out already exists; use --force to overwrite it");
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static FileAttribute<?>[] ownerOnly() {
        try {
            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY)};
            }
        } catch (UnsupportedOperationException e) {
            // no POSIX permissions here: the file gets the default ones
        }
        return new FileAttribute<?>[0];
    }

    static String render(Format format, String query, List<SearchService.Hit> hits) {
        return switch (format) {
            case MD -> markdown(query, hits);
            case CSV -> csv(hits);
            case JSON -> json(query, hits);
        };
    }

    private static String durationText(SearchService.Hit h) {
        return h.duration() == null ? "" : String.format(Locale.ROOT, "%.1f", h.duration());
    }

    private static String statusText(SearchService.Hit h) {
        return new TreeMap<>(h.channelStatus()).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "));
    }

    private static String usedText(SearchService.Hit h) {
        return String.join(",", h.usedOn());
    }

    // CSV: every cell is quoted when needed, and cells that a spreadsheet would run as a formula get a quote in front

    private static String csv(List<SearchService.Hit> hits) {
        StringBuilder sb = new StringBuilder("key,path,duration_s,description,status,used_on\n");
        for (SearchService.Hit h : hits) {
            sb.append(String.join(",", csvCell(h.key()), csvCell(h.path().toString()), csvCell(durationText(h)),
                    csvCell(h.description()), csvCell(statusText(h)), csvCell(usedText(h)))).append('\n');
        }
        return sb.toString();
    }

    static String csvCell(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                || v.startsWith(" ") || v.endsWith(" ")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // Markdown: one table; text that could break a row or start markup is escaped

    private static String markdown(String query, List<SearchService.Hit> hits) {
        StringBuilder sb = new StringBuilder("# Shortlist\n\nQuery: ").append(mdCell(query)).append("\n\n");
        sb.append("| # | Key | Path | Duration (s) | Description | Status | Used on |\n");
        sb.append("|---|-----|------|--------------|-------------|--------|---------|\n");
        int rank = 1;
        for (SearchService.Hit h : hits) {
            sb.append("| ").append(rank++).append(" | ").append(mdCell(h.key())).append(" | ")
                    .append(mdCell(h.path().toString())).append(" | ").append(durationText(h)).append(" | ")
                    .append(mdCell(h.description())).append(" | ").append(mdCell(statusText(h))).append(" | ")
                    .append(mdCell(usedText(h))).append(" |\n");
        }
        return sb.toString();
    }

    static String mdCell(String value) {
        StringBuilder sb = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || Character.isISOControl(cp)) {
                sb.append(' ');
            } else if ("\\|`<>[]".indexOf(cp) >= 0) {
                sb.append('\\').appendCodePoint(cp);
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    private static String json(String query, List<SearchService.Hit> hits) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SearchService.Hit h : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", h.key());
            row.put("path", h.path().toString());
            row.put("duration", h.duration());
            row.put("description", h.description());
            row.put("status", new TreeMap<>(h.channelStatus()));
            row.put("usedOn", h.usedOn());
            rows.add(row);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("query", query);
        doc.put("count", rows.size());
        doc.put("results", rows);
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(doc) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
