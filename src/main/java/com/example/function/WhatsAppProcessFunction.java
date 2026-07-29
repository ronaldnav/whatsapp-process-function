package com.example.function;

import com.example.secrets.SecretCache;
import com.example.service.AdobeDispatchService;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueOutput;
import com.microsoft.azure.functions.annotation.QueueTrigger;

public class WhatsAppProcessFunction {
    private final SecretCache secretCache;
    private final AdobeDispatchService adobeDispatchService;

    public WhatsAppProcessFunction() {
        this(new SecretCache(), new AdobeDispatchService());
    }

    public WhatsAppProcessFunction(SecretCache secretCache, AdobeDispatchService adobeDispatchService) {
        this.secretCache = secretCache;
        this.adobeDispatchService = adobeDispatchService;
    }

    @FunctionName("WhatsAppProcess")
    public void run(
            @QueueTrigger(name = "message",
                    queueName = "%QUEUE_NAME%",
                    connection = "QueueStorage")
            String message,
            @QueueOutput(name = "waProcessEventsPoison",
                    queueName = "%PROCESS_POISON_QUEUE_NAME%",
                    connection = "QueueStorage")
            OutputBinding<String> poisonOutput,
            ExecutionContext context) throws Exception {
        context.getLogger().info("Received message from queue");
        try {
            String cdpApiKey = secretCache.getSecret("AdobeCdpAudienceApiKey");
            String ajoApiKey = secretCache.getSecret("AdobeAjoWebhookApiKey");
            adobeDispatchService.dispatch(message, cdpApiKey, ajoApiKey);
        } catch (Exception ex) {
            context.getLogger().warning("Processing failed, sending event to poison queue: " + ex.getMessage());
            poisonOutput.setValue(message);
        }
    }
}
