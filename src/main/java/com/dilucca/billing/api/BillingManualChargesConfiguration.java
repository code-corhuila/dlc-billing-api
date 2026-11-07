package com.dilucca.billing.api;

import com.dilucca.billing.domain.invoicing.FinalizedAppointments;
import com.dilucca.billing.domain.invoicing.InvoiceDraftUnavailable;
import com.dilucca.billing.infrastructure.persistence.JdbcManualChargeRecording;
import com.dilucca.billing.infrastructure.security.AppointmentFinalizationClient;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class BillingManualChargesConfiguration {
    @Bean JdbcManualChargeRecording manualChargeRecording(JdbcTemplate jdbc,JsonMapper mapper,FinalizedAppointments authority) {
        return new JdbcManualChargeRecording(jdbc,mapper,
                id -> client(authority).readEncounter(id),(patient,dentist) -> client(authority).isAssigned(patient,dentist));
    }
    private AppointmentFinalizationClient client(FinalizedAppointments authority) {
        if(authority instanceof AppointmentFinalizationClient client) return client;
        throw new InvoiceDraftUnavailable();
    }
}
