package com.dilucca.billing.api.manual;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.infrastructure.persistence.JdbcManualChargeRecording;
import com.dilucca.billing.infrastructure.persistence.JdbcManualChargeQuery;
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
class ManualChargeHttpTest {
    static final String PATH="/api/v1/manual-charges";
    final UUID actor=UUID.randomUUID(),key=UUID.randomUUID(),source=UUID.randomUUID(),correlation=UUID.randomUUID();
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean JdbcManualChargeRecording recording;
    @MockitoBean JdbcManualChargeQuery query;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean IamSessionValidationClient iam;
    @BeforeEach void identities() {
        when(iam.isActive(anyString())).thenReturn(true);
        for(String role:List.of("ADMINISTRATOR","DENTIST","SECRETARY_ASSISTANT"))
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256").subject(actor.toString())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("roles",List.of(role)).build());
    }
    String body() { return "{\"patientId\":\""+source+"\",\"appointmentId\":\""+source+"\",\"procedureId\":\""+source
            +"\",\"sourceRecordId\":\""+source+"\",\"itemType\":\"BASE_PROCEDURE\",\"quantity\":\"1\",\"unitPrice\":\"12.34\","
            +"\"currency\":\"COP\",\"concept\":\"Manual concept\",\"justification\":\"Protected reason\"}"; }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String role,String body) {
        return post(PATH).header("Authorization","Bearer "+role).header("Idempotency-Key",key)
                .header("X-Correlation-Id",correlation).contentType("application/json").content(body);
    }
    @Test void derivesActorAndAuthorityRoleAndReturnsPersistedPrivateRepresentation() throws Exception {
        when(recording.record(any(),any(),anyString(),any(),any())).thenReturn(new CreatedResource(201,PATH+"/"+source,"{\"unitPrice\":\"12.34\"}"));
        for(String role:List.of("ADMINISTRATOR","DENTIST")) {
            mvc.perform(request(role,body())).andExpect(status().isCreated()).andExpect(header().string("Location",PATH+"/"+source))
                    .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.unitPrice").value("12.34"));
            verify(recording).record(key,actor,role,ManualChargeInput.parse(body()),correlation);
        }
    }
    @Test void rejectsMalformedInputAndKeysBeforeMoneyTransaction() throws Exception {
        for(String invalid:List.of("null","[]","{}",body()+" {}",body().replace("12.34","0.00"),
                body().replace("\"concept\"","\"recordedBy\""),body().replace("\"quantity\":\"1\"","\"quantity\":\"1\",\"quantity\":\"2\"")))
            mvc.perform(request("ADMINISTRATOR",invalid)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mvc.perform(request("ADMINISTRATOR",body()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization","Bearer ADMINISTRATOR").contentType("application/json").content(body())).andExpect(status().isBadRequest());
        verifyNoInteractions(recording);
    }
    @Test void checksLiveIamAndRoleBeforeEveryAttemptIncludingReplay() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body())).andExpect(status().isUnauthorized());
        mvc.perform(request("SECRETARY_ASSISTANT",body())).andExpect(status().isForbidden());
        when(iam.isActive("DENTIST")).thenReturn(false);
        mvc.perform(request("DENTIST",body())).andExpect(status().isUnauthorized());
        when(iam.isActive("ADMINISTRATOR")).thenThrow(new IllegalStateException("private details"));
        mvc.perform(request("ADMINISTRATOR",body())).andExpect(status().isServiceUnavailable()); verifyNoInteractions(recording);
    }
    @Test void mapsMissingConflictAndDependencyFailuresWithoutPrivateDetails() throws Exception {
        when(recording.record(any(),any(),anyString(),any(),any())).thenThrow(new JdbcManualChargeRecording.Missing());
        mvc.perform(request("ADMINISTRATOR",body())).andExpect(status().isNotFound());
        doThrow(new JdbcManualChargeRecording.Conflict()).when(recording).record(any(),any(),anyString(),any(),any());
        mvc.perform(request("ADMINISTRATOR",body())).andExpect(status().isConflict());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private details")).when(recording).record(any(),any(),anyString(),any(),any());
        mvc.perform(request("ADMINISTRATOR",body())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }
    @Test void protectedReadUsesCurrentActorAndRoleWithNoStoreAndHidesMissingScope() throws Exception {
        when(query.find(any(),any(),anyString())).thenReturn(Map.of("id",source,"justification","Protected reason"));
        for(String role:List.of("ADMINISTRATOR","DENTIST")) {
            mvc.perform(get(PATH+"/"+source).header("Authorization","Bearer "+role)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.justification").value("Protected reason"));
            verify(query).find(source,actor,role);
        }
        mvc.perform(get(PATH+"/"+source).header("Authorization","Bearer SECRETARY_ASSISTANT")).andExpect(status().isForbidden());
        doThrow(new JdbcManualChargeRecording.Missing()).when(query).find(any(),any(),anyString());
        mvc.perform(get(PATH+"/"+source).header("Authorization","Bearer DENTIST")).andExpect(status().isNotFound());
    }
}
