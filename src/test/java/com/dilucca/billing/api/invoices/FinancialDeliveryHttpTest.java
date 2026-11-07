package com.dilucca.billing.api.invoices;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceEventQuery;
import com.dilucca.billing.infrastructure.persistence.JdbcEncounterWriteCoordinator;
import com.dilucca.billing.infrastructure.security.IamSessionValidationClient;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
class FinancialDeliveryHttpTest {
    private static final KeyPair keys=keys();
    private static final Path publicKey=publicKey();
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean JwtDecoder staff;
    @MockitoBean IamSessionValidationClient iam;
    @MockitoBean JdbcInvoiceEventQuery events;
    @MockitoBean JdbcEncounterWriteCoordinator coordination;
    private final UUID invoice=UUID.randomUUID(),event=UUID.randomUUID();
    private static KeyPair keys() {
        try { var g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    private static Path publicKey() {
        try { var p=Files.createTempFile("billing-service-test-",".pem");
            Files.writeString(p,"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n"); return p;
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    @AfterAll static void clean() throws Exception { Files.deleteIfExists(publicKey); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("BILLING_SERVICE_JWT_PUBLIC_KEY_FILE",publicKey::toString);
        p.add("BILLING_SERVICE_JWT_ISSUER",() -> "service-test");
        p.add("BILLING_SERVICE_JWT_AUDIENCE",() -> "billing-private");
        p.add("BILLING_WORKER_SERVICE_SUBJECT",() -> "test-worker");
        p.add("BILLING_CLINICAL_SERVICE_SUBJECT",() -> "test-clinical");
    }
    private String token(String subject,String audience,String scope,KeyPair key) throws Exception {
        var now=Instant.now(); var claims=new JWTClaimsSet.Builder().issuer("service-test").subject(subject).audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).jwtID(UUID.randomUUID().toString()).claim("scope",scope).build();
        var signed=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);
        signed.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate())); return signed.serialize();
    }
    private String path(String kind) { return "/internal/v1/invoices/"+invoice+"/"+kind+"?sourceEventId="+event; }
    @Test void separatesCryptographicServiceTrustSubjectAudienceAndScopeFromStaff() throws Exception {
        mvc.perform(get(path("pdf"))).andExpect(status().isUnauthorized());
        mvc.perform(get(path("pdf")).header("Authorization","Bearer "+token("test-worker","wrong","billing:invoice-pdf:read",keys))).andExpect(status().isUnauthorized());
        mvc.perform(get(path("pdf")).header("Authorization","Bearer "+token("test-worker","billing-private","billing:invoice-pdf:read",keys()))).andExpect(status().isUnauthorized());
        mvc.perform(get(path("pdf")).header("Authorization","Bearer "+token("other","billing-private","billing:invoice-pdf:read",keys))).andExpect(status().isForbidden());
        mvc.perform(get(path("pdf")).header("Authorization","Bearer "+token("test-worker","billing-private","billing:notification-summary:read",keys))).andExpect(status().isForbidden());
        verifyNoInteractions(events,iam,staff);
    }
    @Test void reconstructsOnlyCorrelatedStoredStateAndNeverChecksStaffSession() throws Exception {
        var now=Instant.now();
        when(events.find(invoice,event)).thenReturn(Optional.of(new JdbcInvoiceEventQuery.State(new Invoice(invoice,UUID.randomUUID(),null,"BIL-test",now,
                List.of(),"10.00","6.00","COP","PARTIALLY_PAID",now,now,3),"4.00")));
        mvc.perform(get(path("notification-summary")).header("Authorization","Bearer "+token("test-worker","billing-private","billing:notification-summary:read",keys)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remainingBalance").value("6.00"))
                .andExpect(jsonPath("$.paidTotal").value("4.00")).andExpect(jsonPath("$.recentPaymentAmount").value("4.00"));
        mvc.perform(get(path("pdf")).header("Authorization","Bearer "+token("test-worker","billing-private","billing:invoice-pdf:read",keys)))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        when(events.find(any(),any())).thenReturn(Optional.empty());
        mvc.perform(get(path("pdf")).header("Authorization","Bearer "+token("test-worker","billing-private","billing:invoice-pdf:read",keys))).andExpect(status().isNotFound());
        verifyNoInteractions(iam,staff);
    }
    @Test void onlyClinicalCanFreezeAndReleaseWithStrictCommands() throws Exception {
        UUID operation=UUID.randomUUID(),key=UUID.randomUUID();
        String path="/internal/v1/encounter-accounts/"+invoice+"/"+event+"/manual-charges/freezes";
        String token=token("test-clinical","billing-private","billing:encounter-freeze:write",keys);
        var result=new JdbcEncounterWriteCoordinator.Settlement(operation,"FROZEN",invoice,event,true,0,List.of(),2);
        when(coordination.freezeCommand(any(),anyString(),any(),any(),any())).thenReturn(result);
        when(coordination.releaseCommand(any(),anyString(),any(),any(),any())).thenReturn(result);
        mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"operationId\":\""+operation+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.settled").value(true)).andExpect(jsonPath("$.chargeIds").isEmpty());
        mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"operationId\":\""+operation+"\",\"actor\":\"fake\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key","invalid")
                .contentType("application/json").content("{\"operationId\":\""+operation+"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path+"/"+operation+"/releases").header("Authorization","Bearer "+token).header("Idempotency-Key",key))
                .andExpect(status().isOk());
        mvc.perform(post(path).header("Authorization","Bearer "+token("test-worker","billing-private","billing:encounter-freeze:write",keys)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(iam,staff);
    }
}
