package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dilucca.billing.api.pricing.Price;
import com.dilucca.billing.api.pricing.PricePage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ProcedurePriceQueryRepositoryTest {

    @Test
    void listsPricesWithStableOrderingAndPagination() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcProcedurePriceQueryRepository repository =
                new JdbcProcedurePriceQueryRepository(jdbc);

        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(41L);

        PricePage result = repository.list(2, 20);

        assertTrue(result.data().isEmpty());
        assertEquals(2, result.meta().page());
        assertEquals(20, result.meta().limit());
        assertEquals(41L, result.meta().total());
        assertEquals(3, result.meta().totalPages());

        ArgumentCaptor<String> sql =
                ArgumentCaptor.forClass(String.class);

        verify(jdbc).query(
                sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<Price>>any(),
                eq(20),
                eq(20));

        String statement = sql.getValue();

        assertTrue(statement.contains("billing.procedure_prices"));
        assertTrue(statement.contains(
                "ORDER BY created_at ASC, price_id ASC"));
        assertTrue(statement.contains("LIMIT ? OFFSET ?"));

        verify(jdbc).queryForObject(
                anyString(),
                eq(Long.class));
    }
}
