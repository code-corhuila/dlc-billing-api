package com.dilucca.billing.api.items;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dilucca.billing.infrastructure.persistence.JdbcBillableItemQueryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BillableItemControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new BillableItemController(new JdbcBillableItemQueryRepository(jdbc))).build();

    @Test
    void returnsAnActualPaginatedProjection() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(41L);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());
        mvc.perform(get("/api/v1/billable-items").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.total").value(41))
                .andExpect(jsonPath("$.meta.totalPages").value(3));
    }

    @Test
    void rejectsInvalidPaginationBeforeReadingPersistence() throws Exception {
        for (String query : List.of("page=0", "limit=101", "limit=0", "page=2147483647", "page=no")) {
            mvc.perform(get("/api/v1/billable-items?"+query)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMalformedAndNoncanonicalIdentifiersBeforeReadingPersistence() throws Exception {
        for (String name : List.of("patientId", "procedureId")) {
            for (String value : List.of("not-a-uuid", "1-1-1-1-1", "", "' OR TRUE --")) {
                mvc.perform(get("/api/v1/billable-items").param(name, value))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(jdbc);
    }
}
