package io.github.deval123.nightshelf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hybrid search over described clips: full-text (FTS5) and semantic (cosine
 * similarity over stored vectors), merged with reciprocal rank fusion. If the
 * query cannot be embedded, the result is full-text only and a note says so.
 */
public final class SearchService {

    static final int RRF_K = 60;
    private static final int CANDIDATES = 1000;
    private static final int DESCRIPTION_WIDTH = 120;

    /** One result; {@code path} has been checked to lie inside the clip library. */
    public record Hit(
            String key,
            double score,
            Double similarity,
            Double duration,
            String description,
            Path path,
            Map<String, String> channelStatus,
            List<String> usedOn) {
    }

    public record Result(List<Hit> hits, List<String> notes) {
    }

    private SearchService() {
    }

    /**
     * @param embedder   null when no Ollama is configured
     * @param clips      what is on disk, by key
     * @param catalog    read-only metadata used for filters and per-channel status
     */
    public static Result search(ClipIndex index, Embedder embedder, String embedModel, String query,
            SearchFilter filter, int limit, Path clipbankDir, Map<String, Clip> clips, Catalog catalog) {
        return search(index, embedder, embedModel, query, filter, limit, clipbankDir, clips, catalog, null);
    }

    /**
     * @param minSimilarity when set, only hits with a cosine similarity of at least this value are kept; hits
     *                      that came from full-text search only have none and are dropped
     */
    public static Result search(ClipIndex index, Embedder embedder, String embedModel, String query,
            SearchFilter filter, int limit, Path clipbankDir, Map<String, Clip> clips, Catalog catalog,
            Double minSimilarity) {
        if (FtsQuery.match(query) == null) {
            throw new IllegalArgumentException("the query has no searchable words");
        }
        Map<String, Catalog.Entry> entries = new HashMap<>();
        for (Catalog.Entry e : catalog.clips()) {
            entries.put(e.source() + "/" + e.id(), e);
        }
        Set<String> allowed = new HashSet<>();
        for (String key : clips.keySet()) {
            if (filter.matches(entries.get(key))) {
                allowed.add(key);
            }
        }

        List<String> notes = new ArrayList<>();
        List<String> text = index.ftsSearch(query, CANDIDATES).stream().filter(allowed::contains).toList();
        Map<String, Double> similarity = new HashMap<>();
        List<String> semantic = semanticRanking(index, embedder, embedModel, query, allowed, notes, similarity);

        Map<String, Double> scores = new HashMap<>();
        addRanks(scores, text);
        addRanks(scores, semantic);
        List<String> ordered = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();

        List<Hit> hits = new ArrayList<>();
        int skipped = 0;
        int belowMinimum = 0;
        for (String key : ordered) {
            if (hits.size() >= limit) {
                break;
            }
            Double sim = similarity.get(key);
            if (minSimilarity != null && (sim == null || sim < minSimilarity)) {
                belowMinimum++;
                continue;
            }
            Hit hit = toHit(index, key, scores.get(key), sim, clipbankDir, clips.get(key), entries.get(key));
            if (hit == null) {
                skipped++;
            } else {
                hits.add(hit);
            }
        }
        if (belowMinimum > 0) {
            notes.add(belowMinimum + " result(s) dropped by --min-similarity (full-text-only results have no similarity)");
        }
        if (skipped > 0) {
            notes.add(skipped + " result(s) left out: file missing or outside the clip library");
        }
        return new Result(List.copyOf(hits), List.copyOf(notes));
    }

    private static List<String> semanticRanking(ClipIndex index, Embedder embedder, String model, String query,
            Set<String> allowed, List<String> notes, Map<String, Double> similarity) {
        if (embedder == null) {
            notes.add("OLLAMA_URL is not set; using full-text only");
            return List.of();
        }
        Map<String, float[]> vectors = index.currentEmbeddings(model);
        if (vectors.isEmpty()) {
            notes.add("no embeddings for model " + Sanitize.clean(model, 80) + " yet (run embed); using full-text only");
            return List.of();
        }
        float[] q;
        try {
            q = embedder.embed(query);
        } catch (EmbedException e) {
            notes.add("semantic search unavailable (" + e.getMessage() + "); using full-text only");
            return List.of();
        }
        Map<String, Double> sims = new HashMap<>();
        for (Map.Entry<String, float[]> v : vectors.entrySet()) {
            if (allowed.contains(v.getKey()) && v.getValue().length == q.length) {
                sims.put(v.getKey(), Vectors.cosine(q, v.getValue()));
            }
        }
        similarity.putAll(sims);
        return sims.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .limit(CANDIDATES)
                .toList();
    }

    /** Reciprocal rank fusion: each list adds 1 / (k + rank), ranks starting at 1. */
    private static void addRanks(Map<String, Double> scores, List<String> ranking) {
        for (int i = 0; i < ranking.size(); i++) {
            scores.merge(ranking.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    private static Hit toHit(ClipIndex index, String key, double score, Double similarity, Path clipbankDir, Clip clip,
            Catalog.Entry entry) {
        if (clip == null) {
            return null;
        }
        Path path;
        try {
            path = ClipPaths.requireInside(clipbankDir, clip.path());
        } catch (FrameException e) {
            return null;
        }
        StoredClip stored = index.get(key).orElse(null);
        if (stored == null || stored.description() == null) {
            return null;
        }
        Map<String, String> status = entry == null || entry.status() == null ? Map.of() : Map.copyOf(entry.status());
        List<String> used = entry == null || entry.uses() == null ? List.of()
                : entry.uses().stream().map(Catalog.Use::channel).filter(c -> c != null).distinct().sorted().toList();
        return new Hit(key, score, similarity, entry == null ? null : entry.duration(),
                Sanitize.cleanAtWord(DisplayText.withoutFraming(stored.description().description()), DESCRIPTION_WIDTH), path, status, used);
    }
}
