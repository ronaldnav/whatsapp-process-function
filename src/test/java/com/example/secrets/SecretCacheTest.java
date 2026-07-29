package com.example.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import org.junit.jupiter.api.Test;

class SecretCacheTest {

    @Test
    void getSecret_usesEnvironmentFallbackWhenNoKeyVaultConfigured() {
        SecretCache cache = new SecretCache((SecretClient) null);

        assertEquals("test-key", cache.getSecret("AdobeCdpAudienceApiKey"));
    }

    @Test
    void getSecret_prefersKeyVaultOverEnvironmentWhenConfigured() {
        SecretClient client = mock(SecretClient.class);
        KeyVaultSecret secret = mock(KeyVaultSecret.class);
        when(secret.getValue()).thenReturn("vault-value");
        when(client.getSecret("AdobeCdpAudienceApiKey")).thenReturn(secret);

        SecretCache cache = new SecretCache(client);

        // Even though ADOBE_CDP_AUDIENCE_API_KEY=test-key is present in the test env,
        // Key Vault must win once a SecretClient is configured.
        assertEquals("vault-value", cache.getSecret("AdobeCdpAudienceApiKey"));
    }

    @Test
    void getSecret_throwsClearErrorWhenNoKeyVaultAndNoEnvironmentFallback() {
        SecretCache cache = new SecretCache((SecretClient) null);

        assertThrows(IllegalStateException.class, () -> cache.getSecret("UnmappedSecret"));
    }
}
