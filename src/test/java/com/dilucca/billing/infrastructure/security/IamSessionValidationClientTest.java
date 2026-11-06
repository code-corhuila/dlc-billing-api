package com.dilucca.billing.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class IamSessionValidationClientTest {
    private HttpServer server;
    private URI origin;
    private int status = 200;
    private String response = "{\"active\":true}";
    private int delayMillis;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> service = new AtomicReference<>("service-token");
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();

    @BeforeEach
    void listen() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/sessions/validations", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { Thread.sleep(delayMillis); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", origin + "/must-not-follow");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/must-not-follow", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() { server.stop(0); }

    private IamSessionValidationClient client() {
        return new IamSessionValidationClient(origin, service::get, Duration.ofSeconds(2), true);
    }

    @Test
    void usesPrivateContractAndSeparateCredentialRereadOnEveryCall() {
        var client = client();
        assertTrue(client.isActive("staff-token"));
        assertEquals("POST", method.get());
        assertEquals("Bearer service-token", authorization.get());
        assertEquals(JsonMapper.builder().build().readTree("{\"accessToken\":\"staff-token\"}"),
                JsonMapper.builder().build().readTree(body.get()));
        service.set("rotated-service-token");
        assertTrue(client.isActive("staff-token"));
        assertEquals("Bearer rotated-service-token", authorization.get());
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotCacheSessionEligibility() {
        var client = client();
        assertTrue(client.isActive("staff-token"));
        response = "{\"active\":false}";
        assertFalse(client.isActive("staff-token"));
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 308, 400, 401, 403, 404, 429, 500, 503})
    void rejectsEveryNon200WithoutRedirectOrRetry(int code) {
        status = code;
        var error = assertThrows(IllegalStateException.class, () -> client().isActive("staff-token"));
        assertEquals("IAM session validation unavailable", error.getMessage());
        assertNull(error.getCause());
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "true", "broken", "{\"active\":\"true\"}",
            "{\"active\":1}", "{\"active\":true,\"roles\":[]}", "{\"active\":true} {}"})
    void rejectsMalformedOrUnexpectedResponses(String value) {
        response = value;
        assertThrows(IllegalStateException.class, () -> client().isActive("staff-token"));
    }

    @Test
    void rejectsMissingOrStaffCredentialBeforeDispatch() {
        for (String value : new String[] {null, "", " ", "staff-token", "bad\r\nheader"}) {
            service.set(value);
            assertThrows(IllegalStateException.class, () -> client().isActive("staff-token"));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsUntrustedDestinationConfiguration() {
        for (String value : new String[] {"http://example.test", "https://user:pass@example.test",
                "https://example.test/path", "https://example.test?token=secret", "https://example.test#x"}) {
            assertThrows(IllegalArgumentException.class, () -> new IamSessionValidationClient(
                    URI.create(value), service::get, Duration.ofSeconds(2), false));
        }
    }

    @Test
    void boundsRequestTimeAndDoesNotRevealProviderFailures() {
        delayMillis = 500;
        var bounded = new IamSessionValidationClient(origin, service::get, Duration.ofMillis(100), true);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThrows(IllegalStateException.class, () -> bounded.isActive("staff-token")));
        var brokenProvider = new IamSessionValidationClient(origin, () -> {
            throw new IllegalStateException("sensitive-provider-detail");
        }, Duration.ofSeconds(2), true);
        var error = assertThrows(IllegalStateException.class, () -> brokenProvider.isActive("staff-token"));
        assertEquals("IAM session validation unavailable", error.getMessage());
        assertNull(error.getCause());
    }
}
