package com.dilucca.billing.api.items;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.infrastructure.persistence.JdbcPriceCorrectionRecording;
import com.dilucca.billing.infrastructure.security.IamSessionValidationClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
class PriceCorrectionHttpTest {
    final UUID actor=UUID.randomUUID(),item=UUID.randomUUID(),key=UUID.randomUUID();
    final String body="{\"unitPrice\":\"37.11\",\"reason\":\"Administrative correction\",\"expectedVersion\":1}";
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean JdbcPriceCorrectionRecording recording;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean IamSessionValidationClient iam;
    @BeforeEach void identities() {
        when(iam.isActive(anyString())).thenReturn(true);
        for(String role:List.of("ADMINISTRATOR","DENTIST","SECRETARY_ASSISTANT"))
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256").subject(actor.toString())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("roles",List.of(role)).build());
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String role,String json) {
        return post("/api/v1/billable-items/"+item+"/approvals").header("Authorization","Bearer "+role)
                .header("Idempotency-Key",key).contentType("application/json").content(json);
    }
    @Test void derivesActorAndReturnsStoredFinancialRepresentation() throws Exception {
        when(recording.record(any(),any(),any(),any())).thenReturn(new CreatedResource(200,"/api/v1/billable-items/"+item,"{\"unitPrice\":\"37.11\",\"approvalRequired\":false}"));
        mvc.perform(request("ADMINISTRATOR",body)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.unitPrice").value("37.11"));
        verify(recording).record(key,actor,item,PriceCorrectionInput.parse(body));
    }
    @Test void forbidsOtherRolesAndRequiresLiveSession() throws Exception {
        for(String role:List.of("DENTIST","SECRETARY_ASSISTANT")) mvc.perform(request(role,body)).andExpect(status().isForbidden());
        when(iam.isActive("ADMINISTRATOR")).thenReturn(false);
        mvc.perform(request("ADMINISTRATOR",body)).andExpect(status().isUnauthorized()); verifyNoInteractions(recording);
    }
    @Test void rejectsMalformedRequestsBeforeTransaction() throws Exception {
        for(String invalid:List.of("{}",body+" {}",body.replace("37.11","0.00"),body.replace("\"reason\"","\"actor\"")))
            mvc.perform(request("ADMINISTRATOR",invalid)).andExpect(status().isBadRequest());
        mvc.perform(request("ADMINISTRATOR",body).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/billable-items/"+item+"/approvals").header("Authorization","Bearer ADMINISTRATOR")
                .contentType("application/json").content(body)).andExpect(status().isBadRequest()); verifyNoInteractions(recording);
    }
    @Test void mapsMissingConflictAndPersistenceFailureWithoutLeakingDetails() throws Exception {
        when(recording.record(any(),any(),any(),any())).thenThrow(new JdbcPriceCorrectionRecording.Missing());
        mvc.perform(request("ADMINISTRATOR",body)).andExpect(status().isNotFound());
        doThrow(new JdbcPriceCorrectionRecording.Conflict("CORRECTION_CONFLICT")).when(recording).record(any(),any(),any(),any());
        mvc.perform(request("ADMINISTRATOR",body)).andExpect(status().isConflict());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private connection details")).when(recording).record(any(),any(),any(),any());
        mvc.perform(request("ADMINISTRATOR",body)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }
}
