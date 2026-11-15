package com.dilucca.billing.api.invoices;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.domain.invoicing.*;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceDraftCreation;
import com.dilucca.billing.infrastructure.security.IamSessionValidationClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
class InvoiceDraftHttpTest {
    private static final String PATH="/api/v1/invoices";
    private final UUID patient=UUID.randomUUID(), item=UUID.randomUUID(), key=UUID.randomUUID(), actor=UUID.randomUUID();
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean JdbcInvoiceDraftCreation drafts;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean IamSessionValidationClient iam;

    @BeforeEach
    void identities() {
        when(iam.isActive(anyString())).thenReturn(true);
        for (String role:List.of("ADMINISTRATOR","SECRETARY_ASSISTANT","DENTIST")) {
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256")
                    .subject(actor.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                    .claim("roles",List.of(role)).build());
        }
    }
    private String body() { return "{\"patientId\":\""+patient+"\",\"billableItemIds\":[\""+item+"\"]}"; }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String body) {
        return post(PATH).header("Authorization","Bearer ADMINISTRATOR").header("Idempotency-Key",key)
                .contentType("application/json").content(body);
    }

    @Test
    void administrativeRolesReceivePersistedResponseAndLocation() throws Exception {
        when(drafts.create(key,actor,new InvoiceDraft(patient,null,List.of(item))))
                .thenReturn(new CreatedResource(201,PATH+"/"+item,"{\"status\":\"DRAFT\",\"total\":\"10.01\"}"));
        for (String role:List.of("ADMINISTRATOR","SECRETARY_ASSISTANT")) {
            mvc.perform(post(PATH).header("Authorization","Bearer "+role).header("Idempotency-Key",key)
                    .contentType("application/json").content(body())).andExpect(status().isCreated())
                    .andExpect(header().string("Location",PATH+"/"+item))
                    .andExpect(jsonPath("$.total").value("10.01"));
        }
        verify(drafts,times(2)).create(key,actor,new InvoiceDraft(patient,null,List.of(item)));
    }

    @Test
    void rejectsInvalidRequestsBeforeTransaction() throws Exception {
        for (String body:List.of("", "null", "[]", "{}", "{", body()+" {}",
                body().replace(patient.toString(),"1-1-1-1-1"), body().replace(item.toString(),"bad"),
                body().replace("[\""+item+"\"]","[]"), body().replace("[\""+item+"\"]","null"),
                body().replace("[\""+item+"\"]","[\""+item+"\",\""+item+"\"]"),
                body().replace("}",",\"patientId\":\""+patient+"\"}"),
                body().replace("}",",\"appointmentId\":null}"),
                body().replace("}",",\"total\":\"1.00\"}"), body().replace("}",",\"actorId\":\""+actor+"\"}"))) {
            mvc.perform(request(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
        mvc.perform(request(body()).header("Idempotency-Key","invalid")).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization","Bearer ADMINISTRATOR")
                .contentType("application/json").content(body())).andExpect(status().isBadRequest());
        verifyNoInteractions(drafts);
    }

    @Test
    void rechecksRoleAndIamBeforeEveryRequestIncludingReplay() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body())).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization","Bearer DENTIST").contentType("application/json")
                .content(body())).andExpect(status().isForbidden());
        when(iam.isActive("ADMINISTRATOR")).thenReturn(false);
        mvc.perform(request(body())).andExpect(status().isUnauthorized());
        when(iam.isActive("ADMINISTRATOR")).thenThrow(new IllegalStateException("private-detail"));
        mvc.perform(request(body())).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(drafts);
    }

    @Test
    void reportsConflictAndUnavailableWithoutLeakingCauses() throws Exception {
        when(drafts.create(any(),any(),any())).thenThrow(new InvoiceDraftConflict("INVOICE_SOURCE_CONFLICT"));
        mvc.perform(request(body())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVOICE_SOURCE_CONFLICT"));
        doThrow(new InvoiceDraftUnavailable()).when(drafts).create(any(),any(),any());
        mvc.perform(request(body())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }
}
