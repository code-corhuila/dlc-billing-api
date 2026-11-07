package com.dilucca.billing.api.invoices;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.infrastructure.persistence.JdbcPaymentRecording;
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
class PaymentHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean JdbcPaymentRecording recording;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean IamSessionValidationClient iam;
    private final UUID invoice=UUID.randomUUID(),actor=UUID.randomUUID(),key=UUID.randomUUID();
    private final String body="{\"amount\":\"1.01\",\"method\":\"LOCAL\",\"expectedVersion\":2}";
    private String path() { return "/api/v1/invoices/"+invoice+"/payments"; }
    @BeforeEach void identities() {
        when(iam.isActive(anyString())).thenReturn(true);
        for (String role:List.of("ADMINISTRATOR","SECRETARY_ASSISTANT","DENTIST"))
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256").subject(actor.toString())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("roles",List.of(role)).build());
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String value) {
        return post(path()).header("Authorization","Bearer ADMINISTRATOR").header("Idempotency-Key",key)
                .contentType("application/json").content(value);
    }
    @Test void enforcesExactMoneyVersionShapeAndKeyBeforeWriting() throws Exception {
        for(String invalid:List.of(body.replace("\"1.01\"","1.01"),body.replace("1.01","0.00"),body.replace("1.01","1.001"),
                body.replace("1.01","1e2"),body.replace("1.01","1000000000000.00"),body.replace(":2",":2.0"),
                body.replace("LOCAL"," "),body.replace("LOCAL","x".repeat(41)),body.replace("}",",\"actor\":\"fake\"}"),
                body.replace("}",",\"amount\":\"2.00\"}"),body+"{}"))
            mvc.perform(request(invalid)).andExpect(status().isBadRequest());
        mvc.perform(request(body).header("Idempotency-Key","bad")).andExpect(status().isBadRequest());
        mvc.perform(get(path()).header("Authorization","Bearer ADMINISTRATOR").param("page","0")).andExpect(status().isBadRequest());
        verifyNoInteractions(recording);
    }
    @Test void checksRoleAndLiveIamBeforeReplay() throws Exception {
        mvc.perform(post(path())).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).header("Authorization","Bearer DENTIST")).andExpect(status().isForbidden());
        mvc.perform(get(path()).header("Authorization","Bearer DENTIST")).andExpect(status().isForbidden());
        when(iam.isActive("ADMINISTRATOR")).thenReturn(false);
        mvc.perform(request(body)).andExpect(status().isUnauthorized());
        when(iam.isActive("ADMINISTRATOR")).thenThrow(new IllegalStateException("private"));
        mvc.perform(request(body)).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(recording);
    }
    @Test void returnsStoredResultAndSafeFailures() throws Exception {
        when(recording.record(any(),any(),any(),any(),anyString(),anyLong(),any()))
                .thenReturn(new CreatedResource(201,path()+"#payment","{\"amount\":\"1.01\"}"));
        mvc.perform(request(body)).andExpect(status().isCreated()).andExpect(header().string("Location",path()+"#payment"))
                .andExpect(jsonPath("$.amount").value("1.01"));
        doThrow(new JdbcPaymentRecording.Missing()).when(recording).record(any(),any(),any(),any(),anyString(),anyLong(),any());
        mvc.perform(request(body)).andExpect(status().isNotFound());
        doThrow(new JdbcPaymentRecording.Conflict("STALE_VERSION")).when(recording).record(any(),any(),any(),any(),anyString(),anyLong(),any());
        mvc.perform(request(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("STALE_VERSION"));
    }
}
