package com.dilucca.billing.infrastructure.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-020: a service JWT issued by IAM for one audience through client credentials
 * (POST /internal/v1/service-tokens), cached until two thirds of its lifetime. The client secret is reread from
 * its mounted file on each renewal so it can rotate. Failures surface as an unavailable credential.
 */
public final class IamServiceTokenSupplier implements Supplier<String> {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http;
    private final URI endpoint;
    private final String clientId;
    private final Path secretFile;
    private final String body;
    private final Duration timeout;
    private final Clock clock;
    private String cached;
    private Instant renewAt = Instant.MIN;

    public IamServiceTokenSupplier(URI iamOrigin, String clientId, Path secretFile, String audience, String scope,
                                   Duration timeout, Clock clock, boolean localDevelopment) {
        if (iamOrigin == null || !("https".equals(iamOrigin.getScheme())
            || localDevelopment && "http".equals(iamOrigin.getScheme())) || clientId == null || clientId.isBlank()
            || secretFile == null || !secretFile.isAbsolute()) {
            throw new IllegalArgumentException("IAM origin, client id and absolute secret path required");
        }
        this.endpoint = iamOrigin.resolve("/internal/v1/service-tokens");
        this.clientId = clientId;
        this.secretFile = secretFile;
        var request = new java.util.LinkedHashMap<String, String>();
        request.put("audience", audience);
        request.put("scope", scope);
        this.body = JSON.writeValueAsString(request);
        this.timeout = timeout;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public synchronized String get() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(renewAt)) return cached;
        try {
            if (Files.size(secretFile) > 16384) throw new IllegalStateException();
            String secret = Files.readString(secretFile, StandardCharsets.UTF_8).strip();
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret)
                .getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Basic " + basic).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException();
            JsonNode node = JSON.readTree(response.body());
            JsonNode token = node.get("accessToken");
            JsonNode expiresIn = node.get("expiresIn");
            if (token == null || !token.isString() || token.asString().isBlank()
                || expiresIn == null || !expiresIn.canConvertToLong() || expiresIn.asLong() < 3) {
                throw new IllegalStateException();
            }
            cached = token.asString();
            renewAt = now.plusSeconds(expiresIn.asLong() * 2 / 3);
            return cached;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Service credential unavailable");
        } catch (Exception unavailable) {
            throw new IllegalStateException("Service credential unavailable");
        }
    }
}
