package io.github.deval123.nightshelf;

/** A row of the index. {@code description} is null until the clip is described. */
public record StoredClip(
        String key,
        String source,
        String id,
        long size,
        long mtimeSeconds,
        Status status,
        Description description,
        String failureReason,
        boolean removed,
        long createdAt,
        long updatedAt) {

    public enum Status {
        PENDING, DESCRIBED, FAILED;

        String db() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Status fromDb(String value) {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }
}
