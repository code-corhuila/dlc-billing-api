package com.dilucca.billing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
class BillingApplicationTest {

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @Test
    void applicationContextLoads() {
    }
}
