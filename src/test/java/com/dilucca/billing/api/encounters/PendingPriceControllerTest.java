package com.dilucca.billing.api.encounters;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dilucca.billing.infrastructure.persistence.JdbcPendingPriceQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PendingPriceControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new PendingPriceController(new JdbcPendingPriceQuery(jdbc))).build();

    @Test
    void returnsAPaginatedProjection() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(21L);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        mvc.perform(get("/api/v1/encounter-accounts/pending-prices").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.page").value(2)).andExpect(jsonPath("$.meta.total").value(21))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
    }

    @Test
    void rejectsInvalidPaginationBeforeReadingPersistence() throws Exception {
        for (String query : List.of("page=0", "limit=101", "limit=0", "page=2147483647", "page=no")) {
            mvc.perform(get("/api/v1/encounter-accounts/pending-prices?" + query))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void reportsUnavailablePersistence() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        mvc.perform(get("/api/v1/encounter-accounts/pending-prices"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }
}
