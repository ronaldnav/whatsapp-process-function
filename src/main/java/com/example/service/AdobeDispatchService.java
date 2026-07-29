package com.example.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class AdobeDispatchService {
    private final HttpClient httpClient;
    private final TokenBucketRateLimiter cdpLimiter;
    private final TokenBucketRateLimiter ajoLimiter;
    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final Duration requestTimeout;

    public AdobeDispatchService() {
        this(createHttpClient(), createRateLimiter("ADOBE_CDP_AUDIENCE_RATE_LIMIT_TPS", 30),
                createRateLimiter("ADOBE_AJO_WEBHOOK_RATE_LIMIT_TPS", 30), createMaxAttempts(), createInitialBackoffMillis(), createRequestTimeout());
    }

    public AdobeDispatchService(HttpClient httpClient, TokenBucketRateLimiter cdpLimiter, TokenBucketRateLimiter ajoLimiter, int maxAttempts, long initialBackoffMillis) {
        this(httpClient, cdpLimiter, ajoLimiter, maxAttempts, initialBackoffMillis, createRequestTimeout());
    }

    public AdobeDispatchService(HttpClient httpClient, TokenBucketRateLimiter cdpLimiter, TokenBucketRateLimiter ajoLimiter, int maxAttempts, long initialBackoffMillis, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.cdpLimiter = cdpLimiter;
        this.ajoLimiter = ajoLimiter;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
        this.requestTimeout = requestTimeout;
    }

    public void dispatch(String rawEvent, String cdpApiKey, String ajoApiKey) throws Exception {
        Exception cdpFailure = attemptDispatch(() -> dispatchToEndpoint(rawEvent, cdpApiKey, "ADOBE_CDP_AUDIENCE_ENDPOINT", cdpLimiter, true));
        Exception ajoFailure = attemptDispatch(() -> dispatchToEndpoint(rawEvent, ajoApiKey, "ADOBE_AJO_WEBHOOK_ENDPOINT", ajoLimiter, false));

        if (cdpFailure != null && ajoFailure != null) {
            IllegalStateException combined = new IllegalStateException(
                    "Failed to dispatch to both Adobe endpoints: CDP=" + cdpFailure.getMessage() + "; AJO=" + ajoFailure.getMessage());
            combined.addSuppressed(cdpFailure);
            combined.addSuppressed(ajoFailure);
            throw combined;
        }
        if (cdpFailure != null) {
            throw cdpFailure;
        }
        if (ajoFailure != null) {
            throw ajoFailure;
        }
    }

    private interface DispatchAttempt {
        void run() throws Exception;
    }

    private Exception attemptDispatch(DispatchAttempt attempt) throws InterruptedException {
        try {
            attempt.run();
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } catch (Exception ex) {
            return ex;
        }
    }

    private void dispatchToEndpoint(String rawEvent, String apiKey, String endpointEnv, TokenBucketRateLimiter limiter, boolean isCdp) throws Exception {
        String endpoint = resolveSetting(endpointEnv);
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + endpointEnv);
        }

        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            limiter.acquire();
            try {
                HttpResponse<String> response = post(endpoint, rawEvent, apiKey);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return;
                }
                if (response.statusCode() >= 400 && response.statusCode() < 500) {
                    throw new IllegalStateException("Adobe endpoint returned a non-retryable status " + response.statusCode());
                }
                lastException = new IllegalStateException("Adobe endpoint returned transient status " + response.statusCode());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            } catch (Exception ex) {
                lastException = ex;
            }

            if (attempt < maxAttempts) {
                Thread.sleep(initialBackoffMillis * attempt);
            }
        }

        throw new IllegalStateException("Failed to send event after " + maxAttempts + " attempts", lastException);
    }

    private HttpResponse<String> post(String endpoint, String rawEvent, String apiKey) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rawEvent))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient createHttpClient() {
        if (Boolean.parseBoolean(resolveSettingOrDefault("ADOBE_DISABLE_SSL_VALIDATION", "false"))) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, new SecureRandom());
                return HttpClient.newBuilder().sslContext(sslContext).build();
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Unable to configure SSL trust manager", ex);
            }
        }

        return HttpClient.newHttpClient();
    }

    private static TokenBucketRateLimiter createRateLimiter(String envName, int defaultTps) {
        String configured = resolveSetting(envName);
        int permits = configured == null || configured.isBlank() ? defaultTps : Integer.parseInt(configured);
        return new TokenBucketRateLimiter(permits);
    }

    private static int createMaxAttempts() {
        String configured = resolveSetting("ADOBE_MAX_ATTEMPTS");
        if (configured == null || configured.isBlank()) {
            return createDefaultMaxAttempts();
        }

        try {
            int attempts = Integer.parseInt(configured);
            return attempts > 0 ? attempts : createDefaultMaxAttempts();
        } catch (NumberFormatException ex) {
            return createDefaultMaxAttempts();
        }
    }

    private static long createInitialBackoffMillis() {
        String configured = resolveSetting("ADOBE_INITIAL_BACKOFF_MILLIS");
        if (configured == null || configured.isBlank()) {
            return createDefaultInitialBackoffMillis();
        }

        try {
            long backoff = Long.parseLong(configured);
            return backoff >= 0 ? backoff : createDefaultInitialBackoffMillis();
        } catch (NumberFormatException ex) {
            return createDefaultInitialBackoffMillis();
        }
    }

    private static Duration createRequestTimeout() {
        String configured = resolveSetting("ADOBE_HTTP_TIMEOUT_SECONDS");
        if (configured == null || configured.isBlank()) {
            return createDefaultRequestTimeout();
        }

        try {
            int seconds = Integer.parseInt(configured);
            return seconds > 0 ? Duration.ofSeconds(seconds) : createDefaultRequestTimeout();
        } catch (NumberFormatException ex) {
            return createDefaultRequestTimeout();
        }
    }

    private static int createDefaultMaxAttempts() {
        return Integer.parseInt(resolveSettingOrDefault("ADOBE_DEFAULT_MAX_ATTEMPTS", "3"));
    }

    private static long createDefaultInitialBackoffMillis() {
        return Long.parseLong(resolveSettingOrDefault("ADOBE_DEFAULT_INITIAL_BACKOFF_MILLIS", "500"));
    }

    private static Duration createDefaultRequestTimeout() {
        return Duration.ofSeconds(Integer.parseInt(resolveSettingOrDefault("ADOBE_DEFAULT_HTTP_TIMEOUT_SECONDS", "10")));
    }

    private static String resolveSetting(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return System.getProperty(name);
    }

    private static String resolveSettingOrDefault(String name, String defaultValue) {
        String value = resolveSetting(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
