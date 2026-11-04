package com.dilucca.billing.api.pricing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProcedurePriceControllerTest {

    @Test
    void returnsPaginatedProcedurePrices() throws Exception {
        ProcedurePriceQueryService queryService =
                mock(ProcedurePriceQueryService.class);

        when(queryService.list(1, 20)).thenReturn(
                new PricePage(
                        List.of(),
                        new PaginatedMeta(1, 20, 0, 0)));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ProcedurePriceController(queryService))
                .build();

        mvc.perform(get("/procedure-prices")
                        .param("page", "1")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.limit").value(20))
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.meta.totalPages").value(0));

        verify(queryService).list(1, 20);
    }
}
