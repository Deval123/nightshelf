package io.github.deval123.nightshelf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * SQLite's locking and WAL mode are not reliable on network filesystems, so
 * the index must live on a local disk.
 */
final class NetworkFilesystem {

    private static final List<String> NETWORK_PREFIXES = List.of(
            "nfs", "smb", "cifs", "afp", "webdav", "davfs", "ncp", "9p", "afs",
            "ceph", "gluster", "lustre", "sshfs", "fuse.sshfs", "fuse.rclone", "fuse.gluster");

    private NetworkFilesystem() {
    }

    static boolean isNetworkType(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        return NETWORK_PREFIXES.stream().anyMatch(t::startsWith);
    }

    /** @throws IndexException if the directory that holds (or will hold) the file is on a network filesystem */
    static void requireLocal(Path dbFile) {
        Path dir = dbFile.toAbsolutePath().getParent();
        while (dir != null && !Files.exists(dir)) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IndexException("Cannot determine the filesystem of " + dbFile);
        }
        String type;
        try {
            type = Files.getFileStore(dir).type();
        } catch (IOException e) {
            throw new IndexException("Cannot determine the filesystem type of " + dir + ": " + e.getMessage(), e);
        }
        if (isNetworkType(type)) {
            throw new IndexException("Refusing to open the index at " + dbFile + ": it is on a network filesystem ("
                    + type + "). Set DB_PATH to a path on a local disk.");
        }
    }
}
