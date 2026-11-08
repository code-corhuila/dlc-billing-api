package com.dilucca.billing.api.invoices;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.domain.invoicing.*;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceIssuance;
import com.dilucca.billing.infrastructure.security.IamSessionValidationClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
class InvoiceIssuanceHttpTest {
    private final UUID actor=UUID.randomUUID(),key=UUID.randomUUID(),invoice=UUID.randomUUID(),correlation=UUID.randomUUID();
    private final String path="/api/v1/invoices/"+invoice+"/issuances";
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean JdbcInvoiceIssuance issuance;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean IamSessionValidationClient iam;
    @BeforeEach void identities() {
        when(iam.isActive(anyString())).thenReturn(true);
        for (String role:List.of("ADMINISTRATOR","SECRETARY_ASSISTANT","DENTIST")) {
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256")
                    .subject(actor.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                    .claim("roles",List.of(role)).build());
        }
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String body) {
        return post(path).header("Authorization","Bearer ADMINISTRATOR").header("Idempotency-Key",key)
                .header("X-Correlation-Id",correlation).contentType("application/json").content(body);
    }
    @Test void delegatesOnlyConfirmedVersionAndVerifiedActorForAdministrativeRoles() throws Exception {
        when(issuance.issue(key,actor,invoice,1,correlation)).thenReturn(new CreatedResource(200,
                "/api/v1/invoices/"+invoice,"{\"status\":\"ISSUED\",\"version\":2}"));
        for (String role:List.of("ADMINISTRATOR","SECRETARY_ASSISTANT")) {
            mvc.perform(post(path).header("Authorization","Bearer "+role).header("Idempotency-Key",key)
                    .header("X-Correlation-Id",correlation).contentType("application/json").content("{\"expectedVersion\":1}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ISSUED"));
        }
        verify(issuance,times(2)).issue(key,actor,invoice,1,correlation);
    }
    @Test void validatesStrictBodyPathAndKeyBeforeWriting() throws Exception {
        for (String body:List.of("", "null", "[]", "{}", "{", "{\"expectedVersion\":0}",
                "{\"expectedVersion\":1.0}","{\"expectedVersion\":\"1\"}","{\"expectedVersion\":true}",
                "{\"expectedVersion\":1} {}", "{\"expectedVersion\":1,\"expectedVersion\":2}",
                "{\"expectedVersion\":1,\"total\":\"0.01\"}","{\"expectedVersion\":null}")) {
            mvc.perform(request(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
        mvc.perform(request("{\"expectedVersion\":1}").header("Idempotency-Key","invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/invoices/1-1-1-1-1/issuances").header("Authorization","Bearer ADMINISTRATOR")
                .header("Idempotency-Key",key).contentType("application/json").content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(issuance);
    }
    @Test void roleAndLiveSessionAreCheckedBeforeIssuanceAndReplay() throws Exception {
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization","Bearer DENTIST")).andExpect(status().isForbidden());
        when(iam.isActive("ADMINISTRATOR")).thenReturn(false);
        mvc.perform(request("{\"expectedVersion\":1}")).andExpect(status().isUnauthorized());
        when(iam.isActive("ADMINISTRATOR")).thenThrow(new IllegalStateException("private detail"));
        mvc.perform(request("{\"expectedVersion\":1}")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(issuance);
    }
    @Test void mapsMissingConflictAndPersistenceFailureToSafeResponses() throws Exception {
        doThrow(new InvoiceIssuanceMissing()).when(issuance).issue(any(),any(),any(),anyLong(),any());
        mvc.perform(request("{\"expectedVersion\":1}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        doThrow(new InvoiceIssuanceConflict("STALE_VERSION")).when(issuance).issue(any(),any(),any(),anyLong(),any());
        mvc.perform(request("{\"expectedVersion\":1}")).andExpect(status().isConflict());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private DB detail"))
                .when(issuance).issue(any(),any(),any(),anyLong(),any());
        mvc.perform(request("{\"expectedVersion\":1}")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Invoice issuance unavailable"));
    }
}
