package com.dilucca.billing.api.pricing;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProcedurePriceCreateValidationTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ProcedurePriceController(mock(ProcedurePriceQueryService.class),
                    mock(ProcedurePriceCreateService.class)))
            .setCustomArgumentResolvers(
                    new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .build();
    private static final String PATH = "/api/v1/procedure-prices";
    private static final String KEY = "d17204d4-b893-4086-9aca-b5bd04827f52";
    private static final String VALID = """
            {"procedureCode":"CLEAN", "name":"Cleaning", "basePrice":"150000.00",
             "validFrom":"2026-01-01T00:00:00Z"}
            """;

    @Test
    void rejectsInvalidJsonWithoutEnablingIncompleteWrites() throws Exception {
        for (String body : List.of("", "null", "[]", "{", "{}",
                VALID.replace("\"150000.00\"", "150000.00"),
                VALID.replace("150000.00", "0.00"),
                VALID.replace("150000.00", "1.001"),
                VALID.replace("150000.00", "1000000000000.00"),
                VALID.replace("CLEAN", " "),
                VALID.replace("Cleaning", "x".repeat(256)),
                VALID.replace("2026-01-01T00:00:00Z", "2026-01-01"),
                VALID.replace("\"name\":\"Cleaning\",", ""),
                VALID.replace("\"Cleaning\"", "null"),
                VALID.replace("\"CLEAN\"", "true"),
                VALID.replace("}", ",\"version\":2}"))) {
            mvc.perform(post(PATH).header("Idempotency-Key", KEY)
                    .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void requiresCanonicalUuidKey() throws Exception {
        for (String key : List.of("invalid", "1-1-1-1-1")) {
            mvc.perform(post(PATH).header("Idempotency-Key", key)
                    .contentType("application/json").content(VALID))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsMissingStaffIdentityBeforeWriting() throws Exception {
        mvc.perform(post(PATH).header("Idempotency-Key", KEY)
                .contentType("application/json").content(VALID))
                .andExpect(status().isUnauthorized());
    }
}
