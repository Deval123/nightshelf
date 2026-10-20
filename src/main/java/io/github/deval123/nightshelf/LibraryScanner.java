package io.github.deval123.nightshelf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lists the clips of a library laid out as {@code clips/<source>/*.mp4}.
 * Only reads directory entries and file attributes; clip contents are never
 * opened and nothing is written.
 */
public final class LibraryScanner {

    static final String CLIPS_DIR = "clips";
    static final String CATALOG_FILE = "catalog.json";

    private LibraryScanner() {
    }

    /** Reads the catalog (read-only); an absent file gives an empty catalog. */
    public static Catalog loadCatalog(Path clipbankDir) throws IOException {
        Path catalogFile = clipbankDir.resolve(CATALOG_FILE);
        return Files.exists(catalogFile) ? Catalog.read(catalogFile) : Catalog.empty();
    }

    public static List<Clip> scan(Path clipbankDir) throws IOException {
        return scan(clipbankDir, loadCatalog(clipbankDir));
    }

    public static List<Clip> scan(Path clipbankDir, Catalog catalog) throws IOException {

        List<Clip> clips = new ArrayList<>();
        Path clipsDir = clipbankDir.resolve(CLIPS_DIR);
        if (Files.isSymbolicLink(clipsDir)) {
            return clips;
        }
        for (Path sourceDir : list(clipsDir)) {
            // Symlinks are never followed, so nothing outside CLIPBANK_DIR is listed.
            if (!Files.isDirectory(sourceDir, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String source = sourceDir.getFileName().toString();
            for (Path file : list(sourceDir)) {
                if (isClip(file)) {
                    clips.add(toClip(source, file, catalog));
                }
            }
        }
        return clips;
    }

    static boolean isClip(Path file) {
        String name = file.getFileName().toString();
        return !name.startsWith("._")
                && name.toLowerCase(Locale.ROOT).endsWith(".mp4")
                && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    private static Clip toClip(String source, Path file, Catalog catalog) throws IOException {
        String name = file.getFileName().toString();
        Catalog.Entry entry = catalog.find(source, name);
        return new Clip(
                source,
                entry != null && entry.id() != null ? entry.id() : name.substring(0, name.length() - 4),
                file,
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis(),
                entry != null,
                entry != null && entry.status() != null ? Map.copyOf(entry.status()) : Map.of(),
                entry != null && entry.uses() != null ? entry.uses().size() : 0);
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted(Comparator.comparing(Path::toString)).toList();
        } catch (NoSuchFileException e) {
            return List.of();
        }
    }
}
