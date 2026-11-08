package com.dilucca.billing.api;

import com.dilucca.billing.domain.invoicing.FinalizedAppointments;
import com.dilucca.billing.domain.invoicing.InvoiceDraftUnavailable;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceDraftCreation;
import com.dilucca.billing.infrastructure.security.AppointmentFinalizationClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class BillingAppointmentsConfiguration {
    @Bean
    FinalizedAppointments finalizedAppointments(@Value("${BILLING_APPOINTMENTS_ORIGIN:}") String origin,
            @Value("${BILLING_APPOINTMENTS_SERVICE_TOKEN_FILE:}") String file,
            @Value("${BILLING_APPOINTMENTS_LOCAL_DEVELOPMENT:false}") boolean local) {
        if (origin.isBlank() && file.isBlank()) return (appointment,patient) -> { throw new InvoiceDraftUnavailable(); };
        if (origin.isBlank() || file.isBlank() || !Path.of(file).isAbsolute()) {
            throw new IllegalArgumentException("Private Appointments origin and absolute credential path required");
        }
        Path path=Path.of(file);
        return new AppointmentFinalizationClient(URI.create(origin),() -> {
            try {
                if (Files.size(path)>16384) throw new InvoiceDraftUnavailable();
                return Files.readString(path,StandardCharsets.UTF_8).strip();
            } catch (Exception unavailable) { throw new InvoiceDraftUnavailable(); }
        },Duration.ofSeconds(3),local);
    }

    @Bean
    JdbcInvoiceDraftCreation invoiceDraftCreation(JdbcTemplate jdbc,JsonMapper mapper,FinalizedAppointments appointments) {
        return new JdbcInvoiceDraftCreation(jdbc,mapper,appointments);
    }
}
