package com.example.function;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.secrets.SecretCache;
import com.example.service.AdobeDispatchService;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class WhatsAppProcessFunctionTest {

    @Test
    void run_readsSecretsAndDispatches() throws Exception {
        SecretCache secretCache = mock(SecretCache.class);
        AdobeDispatchService dispatchService = mock(AdobeDispatchService.class);
        ExecutionContext context = mock(ExecutionContext.class);

        when(secretCache.getSecret("AdobeCdpAudienceApiKey")).thenReturn("cdp-key");
        when(secretCache.getSecret("AdobeAjoWebhookApiKey")).thenReturn("ajo-key");
        when(context.getLogger()).thenReturn(Logger.getLogger("test"));

        WhatsAppProcessFunction function = new WhatsAppProcessFunction(secretCache, dispatchService);
        OutputBinding<String> poisonOutput = new OutputBinding<>() {
            private String value;

            @Override
            public String getValue() {
                return value;
            }

            @Override
            public void setValue(String value) {
                this.value = value;
            }
        };
        function.run("{\"wamid\":\"1\"}", poisonOutput, context);

        assertEquals(null, poisonOutput.getValue());
        verify(secretCache).getSecret("AdobeCdpAudienceApiKey");
        verify(secretCache).getSecret("AdobeAjoWebhookApiKey");
        verify(dispatchService).dispatch("{\"wamid\":\"1\"}", "cdp-key", "ajo-key");
    }

    @Test
    void run_sendsFailedEventToPoisonQueue() throws Exception {
        SecretCache secretCache = mock(SecretCache.class);
        AdobeDispatchService dispatchService = mock(AdobeDispatchService.class);
        ExecutionContext context = mock(ExecutionContext.class);

        when(secretCache.getSecret("AdobeCdpAudienceApiKey")).thenReturn("cdp-key");
        when(secretCache.getSecret("AdobeAjoWebhookApiKey")).thenReturn("ajo-key");
        when(context.getLogger()).thenReturn(Logger.getLogger("test"));
        doThrow(new RuntimeException("boom")).when(dispatchService).dispatch("{\"wamid\":\"1\"}", "cdp-key", "ajo-key");

        WhatsAppProcessFunction function = new WhatsAppProcessFunction(secretCache, dispatchService);
        OutputBinding<String> poisonOutput = new OutputBinding<>() {
            private String value;

            @Override
            public String getValue() {
                return value;
            }

            @Override
            public void setValue(String value) {
                this.value = value;
            }
        };

        function.run("{\"wamid\":\"1\"}", poisonOutput, context);

        assertEquals("{\"wamid\":\"1\"}", poisonOutput.getValue());
    }
}
