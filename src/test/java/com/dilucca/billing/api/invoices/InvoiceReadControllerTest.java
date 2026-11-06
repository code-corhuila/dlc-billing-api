package com.dilucca.billing.api.invoices;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.dilucca.billing.api.pricing.PaginatedMeta;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceQueryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InvoiceReadControllerTest {
    private final JdbcInvoiceQueryRepository repository = mock(JdbcInvoiceQueryRepository.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new InvoiceReadController(repository)).build();

    @Test
    void listsPersistedInvoicesWithMetadata() throws Exception {
        when(repository.list(1,20,null)).thenReturn(new InvoicePage(List.of(),new PaginatedMeta(1,20,0,0)));
        mvc.perform(get("/api/v1/invoices")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void validatesQueryAndPathBeforeReadingData() throws Exception {
        for (String query : List.of("?page=0","?limit=101","?page=2147483647","?page=x",
                "?patientId=1-1-1-1-1","/not-a-uuid")) {
            mvc.perform(get("/api/v1/invoices"+query)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void reportsMissingOwnedInvoiceWithoutInventingData() throws Exception {
        UUID id=UUID.randomUUID();
        when(repository.find(id)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/invoices/"+id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
