package io.github.deval123.nightshelf;

/**
 * One batch run as stored in the {@code runs} table. {@code finishedAt} is 0
 * while the run is in progress or if the process was killed before it ended.
 */
public record RunRecord(
        long id,
        long startedAt,
        long finishedAt,
        int described,
        int failed,
        int skipped,
        int embedded,
        double totalSeconds,
        double avgSecondsPerClip,
        String visionModel,
        String embedModel,
        String stopReason) {

    public boolean finished() {
        return finishedAt > 0;
    }
}
