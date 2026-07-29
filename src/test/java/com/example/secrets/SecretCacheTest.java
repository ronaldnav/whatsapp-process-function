package com.example.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import org.junit.jupiter.api.Test;

class SecretCacheTest {

    @Test
    void getSecret_usesEnvironmentFallback() {
        SecretClient client = mock(SecretClient.class);
        SecretCache cache = new SecretCache(client);

        assertEquals("test-key", cache.getSecret("AdobeCdpAudienceApiKey"));
    }

    @Test
    void getSecret_usesKeyVaultWhenEnvAbsent() {
        SecretClient client = mock(SecretClient.class);
        KeyVaultSecret secret = mock(KeyVaultSecret.class);
        when(secret.getValue()).thenReturn("vault-value");
        when(client.getSecret("SomeOtherSecret")).thenReturn(secret);

        SecretCache cache = new SecretCache(client);
        assertEquals("vault-value", cache.getSecret("SomeOtherSecret"));
    }
}
