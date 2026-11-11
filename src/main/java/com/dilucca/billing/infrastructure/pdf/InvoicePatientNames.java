package com.dilucca.billing.infrastructure.pdf;

import com.dilucca.billing.api.ServiceCredentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads the Patients-owned billing contact using Billing's service identity. */
@Component
public class InvoicePatientNames {
    private final URI origin;
    private final Supplier<String> credential;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public InvoicePatientNames(@Value("${BILLING_PATIENTS_ORIGIN:}") String configured,
            @Value("${BILLING_PATIENTS_LOCAL_DEVELOPMENT:false}") boolean local, ServiceCredentials credentials) {
        if (configured.isBlank()) { origin = null; credential = null; return; }
        URI value = URI.create(configured);
        if (value.getHost() == null || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null
                || !(value.getPath().isEmpty() || value.getPath().equals("/"))
                || !("https".equals(value.getScheme()) || local && "http".equals(value.getScheme())))
            throw new IllegalArgumentException("Invalid Patients origin");
        origin = value;
        credential = credentials.forAudience("patients-api", "patients:billing-contact:read");
        if (credential == null) throw new IllegalArgumentException("Billing service credential required for Patients");
    }

    public String read(UUID patientId) {
        if (origin == null) return "No disponible";
        try {
            String token = credential.get();
            if (token == null || token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) throw new IllegalStateException();
            var request = HttpRequest.newBuilder(origin.resolve("/internal/v1/patients/" + patientId + "/billing-contact"))
                    .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() == 404) return "No disponible";
                if (response.statusCode() != 200) throw new IllegalStateException();
                byte[] bytes = body.readNBytes(8193);
                if (bytes.length > 8192) throw new IllegalStateException();
                var json = mapper.readTree(bytes);
                var id = json.get("patientId"); var name = json.get("displayName");
                if (id == null || !id.isString() || !patientId.toString().equalsIgnoreCase(id.stringValue())
                        || name == null || !name.isString() || name.stringValue().isBlank()) throw new IllegalStateException();
                return name.stringValue().strip();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InvoicePdfRenderer.Unavailable();
        } catch (Exception failure) { throw new InvoicePdfRenderer.Unavailable(); }
    }
}
