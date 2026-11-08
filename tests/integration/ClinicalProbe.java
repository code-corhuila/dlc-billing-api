// Synthetic canonical source publisher and private HTTP observer; never a Clinical service substitute.
import com.rabbitmq.client.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

class ClinicalProbe {
    public static void main(String[] args) {
        try {
            if(args[0].equals("freeze") || args[0].equals("release")) {
                String record=UUID.fromString(args[1]).toString(),consultation=UUID.fromString(args[2]).toString();
                String operation=UUID.fromString(args[3]).toString(),key=UUID.fromString(args[4]).toString();
                String path="http://billing-service:8080/internal/v1/encounter-accounts/"+record+"/"+consultation+"/manual-charges/freezes";
                boolean release=args[0].equals("release");
                if(release) path+="/"+operation+"/releases";
                var request=HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(10))
                        .header("Authorization","Bearer "+Files.readString(Path.of("/run/clinical/token")).strip())
                        .header("Idempotency-Key",key).header("Content-Type","application/json")
                        .POST(release ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{\"operationId\":\""+operation+"\"}")).build();
                try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
                    var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
                    System.out.println("CLINICAL_PROBE:"+response.statusCode());
                    System.out.println("CLINICAL_PROBE:"+Base64.getEncoder().encodeToString(response.body()));
                }
                return;
            }
            var factory=new ConnectionFactory(); factory.setUri(System.getenv("BILLING_PROBE_URI"));
            factory.setAutomaticRecoveryEnabled(false); factory.setConnectionTimeout(3000);
            factory.setHandshakeTimeout(3000); factory.setChannelRpcTimeout(3000); factory.setShutdownTimeout(3000);
            try(var connection=factory.newConnection();var channel=connection.createChannel()) {
                if(args[0].equals("bind")) {
                    if(!args[1].startsWith("billing-e2e-")) throw new IllegalArgumentException();
                    channel.exchangeDeclare("billing.events","topic",true);
                    channel.queueDeclare(args[1],true,false,false,null); channel.queueBind(args[1],"billing.events","#");
                } else if(args[0].equals("publish")) {
                    if(!Set.of("ProcedureCompleted","ConsultationRecorded","ClinicalCareCompleted").contains(args[1])) throw new IllegalArgumentException();
                    byte[] body=System.in.readAllBytes(); if(body.length>262144) throw new IllegalArgumentException();
                    var returned=new AtomicBoolean(); channel.addReturnListener(message -> returned.set(true));
                    channel.exchangeDeclare("clinical.events","topic",true); channel.confirmSelect();
                    var properties=new AMQP.BasicProperties.Builder().messageId(UUID.fromString(args[2]).toString())
                            .type(args[1]).appId("clinical-service").contentType("application/json").deliveryMode(2).build();
                    channel.basicPublish("clinical.events",args[1],true,properties,body); channel.waitForConfirmsOrDie(3000);
                    if(returned.get()) throw new IllegalStateException();
                } else throw new IllegalArgumentException();
            }
            System.out.println("CLINICAL_PROBE:OK");
        } catch(Exception failure) { System.err.println("Clinical integration probe unavailable"); System.exit(2); }
    }
}
