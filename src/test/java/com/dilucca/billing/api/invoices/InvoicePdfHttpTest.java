package com.dilucca.billing.api.invoices;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceQueryRepository;
import com.dilucca.billing.infrastructure.security.IamSessionValidationClient;
import java.time.Instant;
import java.util.*;
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
class InvoicePdfHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean JdbcInvoiceQueryRepository invoices;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean IamSessionValidationClient iam;
    @Test void requiresLiveAuthorizedSessionAndReturnsPrivateDownloadOr404() throws Exception {
        UUID id=UUID.randomUUID(); String path="/api/v1/invoices/"+id+"/pdf";
        when(iam.isActive(anyString())).thenReturn(true);
        for(String role:List.of("ADMINISTRATOR","SECRETARY_ASSISTANT","DENTIST"))
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256").subject(UUID.randomUUID().toString())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("roles",List.of(role)).build());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer DENTIST")).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization","Bearer ADMINISTRATOR")).andExpect(status().isNotFound());
        var now=Instant.now();
        when(invoices.find(id)).thenReturn(Optional.of(new Invoice(id,UUID.randomUUID(),null,"BIL-test",now,List.of(),
                "10.00","4.00","COP","PARTIALLY_PAID",now,now,3)));
        mvc.perform(get(path).header("Authorization","Bearer SECRETARY_ASSISTANT")).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"invoice-"+id+".pdf\""));
        when(iam.isActive("SECRETARY_ASSISTANT")).thenReturn(false);
        mvc.perform(get(path).header("Authorization","Bearer SECRETARY_ASSISTANT")).andExpect(status().isUnauthorized());
    }
}
