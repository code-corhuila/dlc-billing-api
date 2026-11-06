package com.dilucca.billing.infrastructure.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Private Auth contract. The platform must supply a separately issued service credential. */
public final class IamSessionValidationClient {
    private final URI endpoint;
    private final Supplier<String> serviceCredential;
    private final Duration timeout;
    private final HttpClient transport;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public IamSessionValidationClient(URI origin, Supplier<String> serviceCredential,
            Duration timeout, boolean localDevelopment) {
        Objects.requireNonNull(origin);
        if (origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                || !("https".equals(origin.getScheme())
                    || localDevelopment && "http".equals(origin.getScheme()))) {
            throw new IllegalArgumentException("Invalid private IAM origin");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Invalid private IAM timeout");
        }
        this.endpoint = origin.resolve("/internal/v1/sessions/validations");
        this.serviceCredential = Objects.requireNonNull(serviceCredential);
        this.timeout = timeout;
        this.transport = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** No eligibility cache or automatic application retry; Auth decides on every invocation. */
    public boolean isActive(String accessToken) {
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            String credential = serviceCredential.get();
            if (!tokenText(accessToken) || !tokenText(credential) || credential.equals(accessToken)) {
                throw unavailable();
            }
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + credential)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(Map.of("accessToken", accessToken))))
                    .build();
            pending = transport.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw unavailable();
            var result = mapper.readTree(response.body());
            if (result == null || !result.isObject() || result.size() != 1
                    || !result.has("active") || !result.get("active").isBoolean()) {
                throw unavailable();
            }
            return result.get("active").booleanValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            // Do not propagate transport, provider or JSON details that may contain credentials.
            throw unavailable();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private static boolean tokenText(String value) {
        return value != null && !value.isBlank() && value.chars().noneMatch(Character::isWhitespace);
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("IAM session validation unavailable");
    }
}
