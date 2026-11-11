package com.dilucca.billing.infrastructure.pdf;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dilucca.billing.api.ServiceCredentials;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoicePatientNamesTest {
    @Test void readsTheMatchingPatientUsingBillingCredentials() throws Exception {
        var id=UUID.randomUUID();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/patients/"+id+"/billing-contact", exchange -> {
            assertEquals("Bearer service-token", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body=("{\"patientId\":\""+id+"\",\"displayName\":\"Ana García\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var credentials=mock(ServiceCredentials.class);
            when(credentials.forAudience("patients-api","patients:billing-contact:read")).thenReturn(() -> "service-token");
            var names=new InvoicePatientNames("http://127.0.0.1:"+server.getAddress().getPort(),true,credentials);
            assertEquals("Ana García",names.read(id));
            assertEquals("No disponible",names.read(UUID.randomUUID()));
        } finally { server.stop(0); }
    }
}
