// Test fixture only: separate ephemeral trust for inter-service JWTs, never a production issuer.
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;

class TestServiceIdentity {
    static String encoded(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    static String encoded(String value) { return encoded(value.getBytes(StandardCharsets.UTF_8)); }
    static String unsigned(java.math.BigInteger value) {
        byte[] bytes=value.toByteArray();
        return encoded(bytes[0]==0 ? Arrays.copyOfRange(bytes,1,bytes.length) : bytes);
    }
    static String pem(String type,byte[] value) {
        return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(value)
                +"\n-----END "+type+"-----\n";
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        Files.createDirectories(root.resolve("public"));
        Files.createDirectories(root.resolve("billing"));
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var staff=generator.generateKeyPair(); var service=generator.generateKeyPair();
        Files.writeString(root.resolve("staff-private.pem"),pem("PRIVATE KEY",staff.getPrivate().getEncoded()));
        Files.writeString(root.resolve("staff-public.pem"),pem("PUBLIC KEY",staff.getPublic().getEncoded()));
        var key=(RSAPublicKey)service.getPublic();
        Files.writeString(root.resolve("public/service-jwks.json"),
                "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"integration-service\",\"n\":\""
                +unsigned(key.getModulus())+"\",\"e\":\""+unsigned(key.getPublicExponent())+"\"}]}");
        long now=Instant.now().getEpochSecond();
        String header=encoded("{\"alg\":\"RS256\",\"kid\":\"integration-service\"}");
        String claims=encoded("{\"sub\":\"billing-integration\",\"iss\":\"integration-service-authority\","
                +"\"aud\":\"integration-iam\",\"scope\":\"identity:validate\",\"iat\":"+now
                +",\"exp\":"+(now+600)+",\"jti\":\""+UUID.randomUUID()+"\"}");
        var signature=Signature.getInstance("SHA256withRSA"); signature.initSign(service.getPrivate());
        signature.update((header+"."+claims).getBytes(StandardCharsets.US_ASCII));
        Files.writeString(root.resolve("billing/service-token"),header+"."+claims+"."+encoded(signature.sign()));
        Files.writeString(root.resolve("service-public.pem"),pem("PUBLIC KEY",service.getPublic().getEncoded()));
        Files.createDirectories(root.resolve("billing-appointments"));
        Files.createDirectories(root.resolve("appointments"));
        token(root.resolve("billing-appointments/service-token"),service,"billing-integration",
                "integration-appointments","appointments:encounter-status:read");
        token(root.resolve("appointments/iam-token"),service,"appointments-integration",
                "integration-iam","identity:validate");
        Files.createDirectories(root.resolve("worker"));
        token(root.resolve("worker/token"),service,"worker-integration","integration-billing",
                "billing:invoice-pdf:read billing:notification-summary:read");
    }

    static void token(Path file,KeyPair key,String subject,String audience,String scope) throws Exception {
        long now=Instant.now().getEpochSecond();
        String content=encoded("{\"alg\":\"RS256\",\"kid\":\"integration-service\"}")+"."+encoded(
                "{\"sub\":\""+subject+"\",\"iss\":\"integration-service-authority\",\"aud\":\""+audience
                +"\",\"scope\":\""+scope+"\",\"iat\":"+now+",\"exp\":"+(now+600)
                +",\"jti\":\""+UUID.randomUUID()+"\"}");
        var signature=Signature.getInstance("SHA256withRSA"); signature.initSign(key.getPrivate());
        signature.update(content.getBytes(StandardCharsets.US_ASCII));
        Files.writeString(file,content+"."+encoded(signature.sign()));
    }
}
