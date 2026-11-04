package com.dilucca.billing.api.pricing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class ProcedurePriceSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProcedurePriceQueryService queryService;

    @Test
    void rejectsUnauthenticatedProcedurePriceRequests() throws Exception {
        mvc.perform(get("/procedure-prices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUntrustedIdentityHeaders() throws Exception {
        mvc.perform(get("/procedure-prices")
                        .header("X-User-Id",
                                "00000000-0000-0000-0000-000000000001")
                        .header("X-User-Role", "ADMINISTRATOR"))
                .andExpect(status().isUnauthorized());
    }
}
