package com.example.secrets;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SecretCache {
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();
    private final SecretClient secretClient;

    public SecretCache() {
        this.secretClient = buildSecretClient();
    }

    public SecretCache(SecretClient secretClient) {
        this.secretClient = secretClient;
    }

    public String getSecret(String secretName) {
        CachedSecret cached = cache.get(secretName);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }

        String value = secretClient != null
                ? resolveFromKeyVault(secretName)
                : resolveFromEnvironmentOrThrow(secretName);

        cache.put(secretName, new CachedSecret(value, Instant.now().plus(getCacheTtl())));
        return value;
    }

    private String resolveFromKeyVault(String secretName) {
        try {
            return secretClient.getSecret(secretName).getValue();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to resolve secret " + secretName + " from Key Vault", ex);
        }
    }

    private String resolveFromEnvironmentOrThrow(String secretName) {
        String value = resolveFromEnvironment(secretName);
        if (value == null) {
            throw new IllegalStateException(
                    "Unable to resolve secret " + secretName
                            + ": KEY_VAULT_URI is not configured and no local environment variable fallback was found");
        }
        return value;
    }

    private String resolveFromEnvironment(String secretName) {
        String envName = switch (secretName) {
            case "AdobeCdpAudienceApiKey" -> "ADOBE_CDP_AUDIENCE_API_KEY";
            case "AdobeAjoWebhookApiKey" -> "ADOBE_AJO_WEBHOOK_API_KEY";
            default -> null;
        };
        if (envName == null) {
            return null;
        }
        return System.getenv(envName);
    }

    private Duration getCacheTtl() {
        String configured = resolveSetting("SECRET_CACHE_TTL_HOURS");
        if (configured == null || configured.isBlank()) {
            return createDefaultCacheTtl();
        }

        try {
            int hours = Integer.parseInt(configured);
            return hours > 0 ? Duration.ofHours(hours) : createDefaultCacheTtl();
        } catch (NumberFormatException ex) {
            return createDefaultCacheTtl();
        }
    }

    private Duration createDefaultCacheTtl() {
        return Duration.ofHours(Integer.parseInt(resolveSettingOrDefault("SECRET_DEFAULT_CACHE_TTL_HOURS", "24")));
    }

    private SecretClient buildSecretClient() {
        String keyVaultUri = System.getenv("KEY_VAULT_URI");
        if (keyVaultUri == null || keyVaultUri.isBlank()) {
            return null;
        }
        return new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    private String resolveSetting(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return System.getProperty(name);
    }

    private String resolveSettingOrDefault(String name, String defaultValue) {
        String value = resolveSetting(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record CachedSecret(String value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
