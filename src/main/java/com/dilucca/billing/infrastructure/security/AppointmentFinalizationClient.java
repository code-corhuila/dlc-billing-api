package com.dilucca.billing.infrastructure.security;

import com.dilucca.billing.domain.invoicing.FinalizedAppointments;
import com.dilucca.billing.domain.invoicing.InvoiceDraftConflict;
import com.dilucca.billing.domain.invoicing.InvoiceDraftUnavailable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the owner's terminal fact; never forwards an end-user credential. */
public final class AppointmentFinalizationClient implements FinalizedAppointments {
    private static final Set<String> STATES = Set.of("PROGRAMADA", "CONFIRMADA", "CANCELADA",
            "EN_ATENCION", "NO_ASISTIO", "FINALIZADA");
    private final URI origin;
    private final Supplier<String> credential;
    private final Duration timeout;
    private final HttpClient transport;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public AppointmentFinalizationClient(URI origin, Supplier<String> credential,
            Duration timeout, boolean localDevelopment) {
        Objects.requireNonNull(origin);
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                || origin.getFragment() != null || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                || !("https".equals(origin.getScheme()) || localDevelopment && "http".equals(origin.getScheme()))) {
            throw new IllegalArgumentException("Invalid private Appointments origin");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Invalid private Appointments timeout");
        }
        this.origin = origin;
        this.credential = Objects.requireNonNull(credential);
        this.timeout = timeout;
        this.transport = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public long requireFinalized(UUID appointmentId, UUID patientId) {
        Objects.requireNonNull(patientId);
        var encounter=readEncounter(appointmentId);
        if(!patientId.equals(encounter.patientId()) || !"FINALIZADA".equals(encounter.status())) throw conflict();
        return encounter.version();
    }

    public Encounter readEncounter(UUID appointmentId) {
        Objects.requireNonNull(appointmentId);
        var result=get("/internal/v1/appointments/"+appointmentId+"/encounter-status");
        if(result==null || !result.isObject() || result.size()!=5
                || !result.has("status") || !result.get("status").isString()
                || !STATES.contains(result.get("status").stringValue())) throw new InvoiceDraftUnavailable();
        UUID ownerAppointment=uuid(result,"appointmentId");
        if(!appointmentId.equals(ownerAppointment)) throw conflict();
        return new Encounter(ownerAppointment,uuid(result,"patientId"),uuid(result,"dentistId"),
                result.get("status").stringValue(),version(result,"version"));
    }

    public boolean isAssigned(UUID patientId,UUID dentistId) {
        Objects.requireNonNull(patientId);
        Objects.requireNonNull(dentistId);
        var result=get("/internal/v1/patients/"+patientId+"/assignments?dentistId="+dentistId);
        if(result==null || !result.isObject() || result.size()!=5 || !result.has("assigned")
                || !result.get("assigned").isBoolean() || !result.has("sourceTypes")
                || !result.get("sourceTypes").isArray()) throw new InvoiceDraftUnavailable();
        if(!patientId.equals(uuid(result,"patientId")) || !dentistId.equals(uuid(result,"dentistId")))
            throw new InvoiceDraftUnavailable();
        version(result,"relationshipVersion");
        var sources=new java.util.HashSet<String>();
        for(var source:result.get("sourceTypes")) {
            if(!source.isString() || !Set.of("APPOINTMENT","ONGOING_ASSIGNMENT").contains(source.stringValue())
                    || !sources.add(source.stringValue())) throw new InvoiceDraftUnavailable();
        }
        boolean assigned=result.get("assigned").booleanValue();
        if(assigned==sources.isEmpty()) throw new InvoiceDraftUnavailable();
        return assigned;
    }

    public record Encounter(UUID appointmentId,UUID patientId,UUID dentistId,String status,long version) { }

    private static long version(JsonNode result,String field) {
        var value=result.get(field);
        if(value==null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<1)
            throw new InvoiceDraftUnavailable();
        return value.longValue();
    }

    private JsonNode get(String path) {
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            String token = credential.get();
            if (token == null || token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
                throw new InvoiceDraftUnavailable();
            }
            var request = HttpRequest.newBuilder(origin.resolve(path))
                    .timeout(timeout).header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json").GET().build();
            pending = transport.sendAsync(request, ignored -> new LimitedBody());
            var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() == 404) throw conflict();
            if (response.statusCode() != 200) throw new InvoiceDraftUnavailable();
            return mapper.readTree(response.body());
        } catch (InvoiceDraftConflict conflict) {
            throw conflict;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InvoiceDraftUnavailable();
        } catch (Exception failure) {
            throw new InvoiceDraftUnavailable(); // Never leak provider, transport or owner response details.
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private static UUID uuid(JsonNode result, String field) {
        var value = result.get(field);
        if (value == null || !value.isString()) throw new InvoiceDraftUnavailable();
        UUID parsed;
        try { parsed=UUID.fromString(value.stringValue()); }
        catch(IllegalArgumentException invalid) { throw new InvoiceDraftUnavailable(); }
        if (!parsed.toString().equalsIgnoreCase(value.stringValue())) throw new InvoiceDraftUnavailable();
        return parsed;
    }

    private static InvoiceDraftConflict conflict() { return new InvoiceDraftConflict("APPOINTMENT_NOT_ELIGIBLE"); }

    /** Bound received bytes before delegating to the buffering subscriber. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate =
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private int received;
        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 4096 - received) {
                    subscription.cancel();
                    delegate.onError(new InvoiceDraftUnavailable());
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) { delegate.onError(failure); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
}
