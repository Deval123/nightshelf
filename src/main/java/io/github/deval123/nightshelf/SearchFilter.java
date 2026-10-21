package io.github.deval123.nightshelf;

import java.util.Locale;

/**
 * Filters results with the read-only catalog. With a channel, the status
 * decides: DEFAULT hides clips rejected for that channel, APPROVED and REJECTED
 * keep only those, UNUSED keeps clips never used on that channel and not rejected for it,
 * ANY keeps all.
 */
public record SearchFilter(String channel, Status status, String orientation, Double minDuration) {

    public enum Status {
        DEFAULT, APPROVED, REJECTED, UNUSED, ANY;

        static Status parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "approved" -> APPROVED;
                case "rejected" -> REJECTED;
                case "unused" -> UNUSED;
                case "any" -> ANY;
                default -> throw new IllegalArgumentException(
                        "--status must be approved, rejected, unused or any: " + value);
            };
        }
    }

    public SearchFilter {
        if (status == null) {
            status = Status.DEFAULT;
        }
        if (channel == null && status != Status.DEFAULT) {
            throw new IllegalArgumentException("--status needs --channel");
        }
        if (minDuration != null && (!Double.isFinite(minDuration) || minDuration < 0)) {
            throw new IllegalArgumentException("--min-duration must be a number of seconds, 0 or more");
        }
    }

    public static SearchFilter none() {
        return new SearchFilter(null, Status.DEFAULT, null, null);
    }

    /** @param entry the catalog entry of the clip, or null if the catalog has none */
    boolean matches(Catalog.Entry entry) {
        if (orientation != null
                && (entry == null || entry.orientation() == null || !orientation.equalsIgnoreCase(entry.orientation()))) {
            return false;
        }
        if (minDuration != null && (entry == null || entry.duration() == null || entry.duration() < minDuration)) {
            return false;
        }
        if (channel == null) {
            return true;
        }
        String state = entry == null || entry.status() == null ? null : entry.status().get(channel);
        boolean used = entry != null && entry.uses() != null
                && entry.uses().stream().anyMatch(u -> channel.equals(u.channel()));
        return switch (status) {
            case DEFAULT -> !"rejected".equals(state);
            case APPROVED -> "approved".equals(state);
            case REJECTED -> "rejected".equals(state);
            case UNUSED -> !used && !"rejected".equals(state);
            case ANY -> true;
        };
    }
}
