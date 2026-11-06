package com.dilucca.billing.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;

import com.dilucca.billing.domain.invoicing.InvoiceDraftConflict;
import com.dilucca.billing.domain.invoicing.InvoiceDraftUnavailable;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppointmentFinalizationClientTest {
    private final UUID appointment = UUID.randomUUID(), patient = UUID.randomUUID();
    private final AtomicReference<String> credential = new AtomicReference<>("private-service-token");
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private URI origin;
    private String response;
    private int status = 200, bodyDelay;

    @BeforeEach
    void listen() throws Exception {
        response = valid("FINALIZADA");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("/internal/v1/appointments/" + appointment + "/encounter-status",
                    exchange.getRequestURI().toString());
            exchange.getResponseHeaders().set("Location", origin + "/must-not-follow");
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try {
                if (bodyDelay > 0) Thread.sleep(bodyDelay);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() { server.stop(0); }

    private String valid(String state) {
        return "{\"appointmentId\":\"" + appointment + "\",\"patientId\":\"" + patient
                + "\",\"dentistId\":\"" + UUID.randomUUID() + "\",\"status\":\"" + state + "\",\"version\":7}";
    }

    private AppointmentFinalizationClient client() {
        return new AppointmentFinalizationClient(origin, credential::get, Duration.ofSeconds(2), true);
    }

    @Test
    void readsExactOwnerContractWithRotatablePrivateCredentialAndNoCache() {
        var client = client();
        assertEquals(7, client.requireFinalized(appointment, patient));
        assertEquals("Bearer private-service-token", authorization.get());
        credential.set("rotated-private-token");
        assertEquals(7, client.requireFinalized(appointment, patient));
        assertEquals("Bearer rotated-private-token", authorization.get());
        assertEquals(2, calls.get());
    }

    @Test
    void knownNonFinalStatesAndOwnerMismatchConflict() {
        for (String state : new String[] {"PROGRAMADA", "CONFIRMADA", "EN_ATENCION", "CANCELADA", "NO_ASISTIO"}) {
            response = valid(state);
            assertThrows(InvoiceDraftConflict.class, () -> client().requireFinalized(appointment, patient));
        }
        response = valid("FINALIZADA").replace(patient.toString(), UUID.randomUUID().toString());
        assertThrows(InvoiceDraftConflict.class, () -> client().requireFinalized(appointment, patient));
        response = valid("FINALIZADA").replace(appointment.toString(), UUID.randomUUID().toString());
        assertThrows(InvoiceDraftConflict.class, () -> client().requireFinalized(appointment, patient));
        status = 404;
        assertThrows(InvoiceDraftConflict.class, () -> client().requireFinalized(appointment, patient));
    }

    @Test
    void failsClosedOnInvalidOrUnexpectedOwnerResponse() {
        String good = valid("FINALIZADA");
        for (String body : new String[] {"{}", "[]", "null", "broken", good + " {}",
                good.replace("\"version\":7", "\"version\":7,\"version\":8"),
                good.replace("\"version\":7", "\"version\":0"),
                good.replace("\"version\":7", "\"version\":7.0"),
                good.replace("\"version\":7", "\"version\":9223372036854775808"),
                good.replace("\"version\":7", "\"version\":\"7\""),
                good.replace("FINALIZADA", "UNKNOWN"), good.replace(patient.toString(), "1-1-1-1-1"),
                good.replace("\"version\":7", "\"version\":7,\"extra\":true"), " ".repeat(8192) + good}) {
            response = body;
            var error = assertThrows(InvoiceDraftUnavailable.class,
                    () -> client().requireFinalized(appointment, patient));
            assertNull(error.getCause());
        }
    }

    @Test
    void neverRedirectsOrRetriesAndDoesNotExposeTransportDetails() {
        for (int code : new int[] {301, 302, 307, 308, 400, 401, 403, 429, 500, 503}) {
            int before = calls.get();
            status = code;
            var error = assertThrows(InvoiceDraftUnavailable.class,
                    () -> client().requireFinalized(appointment, patient));
            assertEquals("Appointment validation unavailable", error.getMessage());
            assertNull(error.getCause());
            assertEquals(before + 1, calls.get());
        }
    }

    @Test
    void rejectsInvalidCredentialsBeforeDispatchAndSanitizesProviderFailures() {
        for (String token : new String[] {null, "", " ", "bad\r\nheader"}) {
            credential.set(token);
            assertThrows(InvoiceDraftUnavailable.class, () -> client().requireFinalized(appointment, patient));
        }
        var broken = new AppointmentFinalizationClient(origin, () -> {
            throw new IllegalStateException("sensitive-provider-detail");
        }, Duration.ofSeconds(2), true);
        assertNull(assertThrows(InvoiceDraftUnavailable.class,
                () -> broken.requireFinalized(appointment, patient)).getCause());
        assertEquals(0, calls.get());
    }

    @Test
    void boundsWholeBodyTimeAfterHeaders() {
        bodyDelay = 1500;
        var bounded = new AppointmentFinalizationClient(origin, credential::get, Duration.ofMillis(500), true);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(InvoiceDraftUnavailable.class,
                () -> bounded.requireFinalized(appointment, patient)));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsUntrustedOriginsAndInvalidTimeouts() {
        for (String value : new String[] {"http://example.test", "https://user:pass@example.test",
                "https://example.test/path", "https://example.test?token=secret", "https://example.test#x"}) {
            assertThrows(IllegalArgumentException.class, () -> new AppointmentFinalizationClient(
                    URI.create(value), credential::get, Duration.ofSeconds(2), false));
        }
        for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(11)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AppointmentFinalizationClient(origin, credential::get, timeout, true));
        }
    }
}
