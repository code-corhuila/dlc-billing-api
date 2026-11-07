// Test observer of a real broker queue, not a worker or a business-response substitute.
import com.rabbitmq.client.ConnectionFactory;
import java.util.Base64;

class RabbitProbe {
    static void output(Object value) { System.out.println("BILLING_PROBE:"+value); }
    public static void main(String[] args) {
        try {
            String queue=args[1];
            if (!queue.startsWith("billing-e2e-")) throw new IllegalArgumentException();
            var factory=new ConnectionFactory(); factory.setUri(System.getenv("BILLING_PROBE_URI"));
            factory.setAutomaticRecoveryEnabled(false); factory.setConnectionTimeout(3000);
            factory.setHandshakeTimeout(3000); factory.setChannelRpcTimeout(3000); factory.setShutdownTimeout(3000);
            try (var connection=factory.newConnection();var channel=connection.createChannel()) {
                if (args[0].equals("bind")) {
                    channel.exchangeDeclare("billing.events","topic",true);
                    channel.queueDeclare(queue,true,false,false,null);
                    channel.queueBind(queue,"billing.events","InvoiceIssued");
                    output("BOUND");
                } else if (args[0].equals("receive")) {
                    var message=channel.basicGet(queue,false);
                    if (message==null) { output("EMPTY"); return; }
                    output(Base64.getEncoder().encodeToString(message.getBody()));
                    output(message.getProps().getMessageId());
                    output(message.getProps().getType());
                    output(message.getProps().getContentType());
                    output(message.getProps().getDeliveryMode());
                    channel.basicAck(message.getEnvelope().getDeliveryTag(),false);
                } else throw new IllegalArgumentException();
            }
        } catch (Exception unavailable) {
            System.err.println("Test broker observation unavailable"); System.exit(2);
        }
    }
}
