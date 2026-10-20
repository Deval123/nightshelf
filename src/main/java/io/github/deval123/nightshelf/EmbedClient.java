package io.github.deval123.nightshelf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Embeds text with Ollama ({@code POST /api/embed}). The response is untrusted:
 * it must hold exactly one non-empty vector of finite numbers with the same
 * dimension as before. Redirects are not followed and responses are capped.
 */
public final class EmbedClient implements Embedder {

    static final int MAX_RETRIES = 2;
    static final int MAX_DIMENSION = 8192;
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(2);
    private static final int MAX_RESPONSE_BYTES = 1 << 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final URI embedUri;
    private final String model;
    private final Duration requestTimeout;
    private final Duration backoff;
    private volatile Integer dimension;

    /** @param expectedDimension the dimension already used in the index, or null if there is none yet */
    public EmbedClient(URI baseUrl, String model, Integer expectedDimension) {
        this(baseUrl, model, expectedDimension, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, DEFAULT_BACKOFF);
    }

    EmbedClient(URI baseUrl, String model, Integer expectedDimension, Duration connectTimeout,
            Duration requestTimeout, Duration backoff) {
        String scheme = baseUrl.getScheme() == null ? "" : baseUrl.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Ollama URL must be http or https: " + baseUrl);
        }
        String base = baseUrl.toString();
        this.embedUri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/api/embed");
        this.model = model;
        this.dimension = expectedDimension;
        this.requestTimeout = requestTimeout;
        this.backoff = backoff;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Embeds one text, retrying transient failures up to {@link #MAX_RETRIES} times. */
    @Override
    public float[] embed(String text) throws EmbedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("input", text);
        body.put("truncate", true);
        String json = body.toString();
        EmbedException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                sleep(backoff.multipliedBy(1L << (attempt - 1)));
            }
            try {
                return attemptOnce(json);
            } catch (EmbedException e) {
                if (!e.retryable()) {
                    throw e;
                }
                last = e;
            }
        }
        throw new EmbedException("failed after " + (MAX_RETRIES + 1) + " attempts: " + last.getMessage(), false, last);
    }

    private float[] attemptOnce(String json) throws EmbedException {
        HttpRequest request = HttpRequest.newBuilder(embedUri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<InputStream> response;
        String text;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new EmbedException("response too large", true, null);
                }
                text = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbedException("interrupted", false, e);
        } catch (IOException e) {
            if (e instanceof EmbedException ee) {
                throw ee;
            }
            throw new EmbedException("request to Ollama failed: " + e.getClass().getSimpleName()
                    + " " + e.getMessage(), true, e);
        }
        int status = response.statusCode();
        if (status != 200) {
            throw new EmbedException("Ollama returned HTTP " + status + ": " + Sanitize.clean(text, 200),
                    status >= 500 || status == 429, null);
        }
        return parse(text);
    }

    /** Validates an /api/embed response and the dimension. */
    float[] parse(String responseBody) throws EmbedException {
        JsonNode embeddings;
        try {
            embeddings = MAPPER.readTree(responseBody).path("embeddings");
        } catch (IOException e) {
            throw new EmbedException("response is not valid JSON", true, e);
        }
        if (!embeddings.isArray() || embeddings.size() != 1) {
            throw new EmbedException("expected exactly one embedding", false, null);
        }
        JsonNode vector = embeddings.get(0);
        if (!vector.isArray() || vector.isEmpty() || vector.size() > MAX_DIMENSION) {
            throw new EmbedException("embedding must be a list of 1 to " + MAX_DIMENSION + " numbers", false, null);
        }
        float[] out = new float[vector.size()];
        for (int i = 0; i < out.length; i++) {
            JsonNode n = vector.get(i);
            if (!n.isNumber()) {
                throw new EmbedException("embedding contains a non-number", false, null);
            }
            out[i] = (float) n.asDouble();
        }
        if (!Vectors.allFinite(out)) {
            throw new EmbedException("embedding contains a value that is not finite", false, null);
        }
        Integer expected = dimension;
        if (expected == null) {
            dimension = out.length;
        } else if (expected != out.length) {
            throw new EmbedException("embedding has " + out.length + " dimensions but the index uses "
                    + expected + " for this model", false, null);
        }
        return out;
    }

    private void sleep(Duration d) throws EmbedException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbedException("interrupted", false, e);
        }
    }
}
