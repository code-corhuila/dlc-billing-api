package com.dilucca.billing.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;
import com.dilucca.billing.domain.invoicing.InvoiceDraftUnavailable;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;

/** Contract-boundary isolation; real owner acceptance is a separate integration test. */
class AppointmentCareAuthorityTest {
    final UUID patient=UUID.randomUUID(),dentist=UUID.randomUUID(),appointment=UUID.randomUUID();
    HttpServer server;
    String body,path;
    int calls;
    AppointmentFinalizationClient client;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            calls++; path=exchange.getRequestURI().toString();
            assertEquals("Bearer private-only",exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        }); server.start();
        client=new AppointmentFinalizationClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
                () -> "private-only",Duration.ofSeconds(2),true);
    }
    @AfterEach void stop() { server.stop(0); }
    String assignment(boolean assigned,String sources) {
        return "{\"patientId\":\""+patient+"\",\"dentistId\":\""+dentist+"\",\"assigned\":"+assigned
                +",\"relationshipVersion\":3,\"sourceTypes\":"+sources+"}";
    }
    @Test void readsCurrentAssignmentOnEveryCallUsingOfficialRoute() {
        body=assignment(true,"[\"ONGOING_ASSIGNMENT\"]");
        assertTrue(client.isAssigned(patient,dentist));
        assertEquals("/internal/v1/patients/"+patient+"/assignments?dentistId="+dentist,path);
        body=assignment(false,"[]"); assertFalse(client.isAssigned(patient,dentist)); assertEquals(2,calls);
    }
    @Test void rejectsMalformedAndMismatchedAssignmentProof() {
        String valid=assignment(true,"[\"APPOINTMENT\"]");
        for(String invalid:new String[]{valid.replace(patient.toString(),UUID.randomUUID().toString()),
                valid.replace(dentist.toString(),UUID.randomUUID().toString()),
                valid.replace(":3",":0"),valid.replace("APPOINTMENT","UNKNOWN"),
                assignment(true,"[]"),assignment(false,"[\"APPOINTMENT\"]"),
                assignment(true,"[\"APPOINTMENT\",\"APPOINTMENT\"]"),valid+" {}",
                valid.replace("\"assigned\":true","\"assigned\":true,\"assigned\":false")}) {
            body=invalid; assertThrows(InvoiceDraftUnavailable.class,() -> client.isAssigned(patient,dentist));
        }
    }
    @Test void readsCareStateWithoutMistakingItForFinalization() {
        body="{\"appointmentId\":\""+appointment+"\",\"patientId\":\""+patient+"\",\"dentistId\":\""+dentist
                +"\",\"status\":\"EN_ATENCION\",\"version\":8}";
        var encounter=client.readEncounter(appointment);
        assertEquals(patient,encounter.patientId()); assertEquals(dentist,encounter.dentistId());
        assertEquals("EN_ATENCION",encounter.status()); assertEquals(8,encounter.version());
    }
}
