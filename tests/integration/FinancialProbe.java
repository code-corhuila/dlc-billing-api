// Test observer of the actual private HTTP service, never a provider stub.
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

class FinancialProbe {
    public static void main(String[] args) throws Exception {
        String invoice=UUID.fromString(args[1]).toString(),event=UUID.fromString(args[2]).toString();
        String operation=switch(args[0]) { case "pdf" -> "pdf"; case "summary" -> "notification-summary";
            default -> throw new IllegalArgumentException(); };
        String token=Files.readString(Path.of("/run/worker/token")).strip();
        var request=HttpRequest.newBuilder(URI.create("http://billing-service:8080/internal/v1/invoices/"+invoice+"/"+operation+"?sourceEventId="+event))
                .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).GET().build();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
            System.out.println("FINANCIAL_PROBE:"+response.statusCode());
            System.out.println("FINANCIAL_PROBE:"+response.headers().firstValue("Content-Type").orElse(""));
            System.out.println("FINANCIAL_PROBE:"+Base64.getEncoder().encodeToString(response.body()));
        }
    }
}
