// Observe the existing owner's DB readiness; no credentials or provider stubs.
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

class OwnerReadinessProbe {
    public static void main(String[] args) throws Exception {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var request=HttpRequest.newBuilder(URI.create("http://appointments-api:8080/health/ready"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            for(int attempt=0;attempt<10;attempt++) {
                try {
                    if(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200) {
                        System.out.println("APPOINTMENTS_READY"); return;
                    }
                } catch(java.io.IOException unavailable) { /* Readiness is bounded and side-effect free. */ }
                Thread.sleep(500);
            }
            throw new IllegalStateException("Appointments database readiness unavailable");
        }
    }
}
