package com.dilucca.billing.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BillingIamCredentialFileTest {
    @TempDir Path directory;

    @Test
    void rereadsTheProvisionedFileAndDeniesMissingCredentials() throws Exception {
        var seen = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/sessions/validations", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"active\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try (var output=exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            Path credential=directory.resolve("service-token");
            var client=new BillingIamSessionConfiguration().iamSessionValidationClient(
                    "http://127.0.0.1:"+server.getAddress().getPort(),credential.toString(),true,
                    new ServiceCredentials("","billing-api","",true));
            assertThrows(IllegalStateException.class,()->client.isActive("staff-token"));
            Files.writeString(credential,"first-service-token\n");
            assertTrue(client.isActive("staff-token"));
            assertEquals("Bearer first-service-token",seen.get());
            Path replacement=directory.resolve("replacement");
            Files.writeString(replacement,"rotated-service-token");
            Files.move(replacement,credential,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertTrue(client.isActive("staff-token"));
            assertEquals("Bearer rotated-service-token",seen.get());
            Files.delete(credential);
            assertThrows(IllegalStateException.class,()->client.isActive("staff-token"));
        } finally { server.stop(0); }
    }
}
