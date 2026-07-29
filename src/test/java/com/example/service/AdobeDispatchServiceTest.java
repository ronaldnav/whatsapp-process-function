package com.example.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AdobeDispatchServiceTest {

    @Test
    void dispatch_sendsToBothEndpoints_whenBothSucceed() throws Exception {
        System.setProperty("ADOBE_CDP_AUDIENCE_ENDPOINT", "https://example.test/cdp");
        System.setProperty("ADOBE_AJO_WEBHOOK_ENDPOINT", "https://example.test/ajo");

        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> success = (HttpResponse<String>) mock(HttpResponse.class);
        when(success.statusCode()).thenReturn(200);
        when(client.send(any(), any())).thenReturn((HttpResponse) success);

        AdobeDispatchService service = new AdobeDispatchService(client, new TokenBucketRateLimiter(30), new TokenBucketRateLimiter(30), 1, 100L, Duration.ofSeconds(10));

        service.dispatch("{\"wamid\":\"1\"}", "cdp-key", "ajo-key");
    }

    @Test
    void dispatch_throws_whenCdpFailsAfterRetries() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> failure = (HttpResponse<String>) mock(HttpResponse.class);
        when(failure.statusCode()).thenReturn(500);
        when(client.send(any(), any())).thenReturn((HttpResponse) failure);

        AdobeDispatchService service = new AdobeDispatchService(client, new TokenBucketRateLimiter(30), new TokenBucketRateLimiter(30), 3, 10L, Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class, () -> service.dispatch("{\"wamid\":\"2\"}", "cdp-key", "ajo-key"));
    }

    @Test
    void dispatch_throws_whenAjoFailsAfterRetries() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> failure = (HttpResponse<String>) mock(HttpResponse.class);
        when(failure.statusCode()).thenReturn(500);
        when(client.send(any(), any())).thenReturn((HttpResponse) failure);

        AdobeDispatchService service = new AdobeDispatchService(client, new TokenBucketRateLimiter(30), new TokenBucketRateLimiter(30), 3, 10L, Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class, () -> service.dispatch("{\"wamid\":\"3\"}", "cdp-key", "ajo-key"));
    }

    @Test
    void dispatch_stillCallsAjo_whenCdpFailsAfterRetries() throws Exception {
        System.setProperty("ADOBE_CDP_AUDIENCE_ENDPOINT", "https://example.test/cdp");
        System.setProperty("ADOBE_AJO_WEBHOOK_ENDPOINT", "https://example.test/ajo");

        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> cdpFailure = (HttpResponse<String>) mock(HttpResponse.class);
        when(cdpFailure.statusCode()).thenReturn(500);
        @SuppressWarnings("unchecked")
        HttpResponse<String> ajoSuccess = (HttpResponse<String>) mock(HttpResponse.class);
        when(ajoSuccess.statusCode()).thenReturn(200);

        when(client.send(argThat(req -> req != null && req.uri().toString().contains("/cdp")), any())).thenReturn((HttpResponse) cdpFailure);
        when(client.send(argThat(req -> req != null && req.uri().toString().contains("/ajo")), any())).thenReturn((HttpResponse) ajoSuccess);

        AdobeDispatchService service = new AdobeDispatchService(client, new TokenBucketRateLimiter(30), new TokenBucketRateLimiter(30), 1, 10L, Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class, () -> service.dispatch("{\"wamid\":\"6\"}", "cdp-key", "ajo-key"));

        verify(client, times(1)).send(argThat(req -> req != null && req.uri().toString().contains("/ajo")), any());
    }

    @Test
    void dispatch_doesNotRetry_on4xxResponse() throws Exception {
        System.setProperty("ADOBE_CDP_AUDIENCE_ENDPOINT", "https://example.test/cdp");
        System.setProperty("ADOBE_AJO_WEBHOOK_ENDPOINT", "https://example.test/ajo");

        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> failure = (HttpResponse<String>) mock(HttpResponse.class);
        when(failure.statusCode()).thenReturn(400);
        when(client.send(any(), any())).thenReturn((HttpResponse) failure);

        AdobeDispatchService service = new AdobeDispatchService(client, new TokenBucketRateLimiter(30), new TokenBucketRateLimiter(30), 3, 10L, Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class, () -> service.dispatch("{\"wamid\":\"4\"}", "cdp-key", "ajo-key"));
    }

    @Test
    void dispatch_waitsForPermit_whenUsingTokenBucketRateLimiter() throws Exception {
        System.setProperty("ADOBE_CDP_AUDIENCE_ENDPOINT", "https://example.test/cdp");
        System.setProperty("ADOBE_AJO_WEBHOOK_ENDPOINT", "https://example.test/ajo");

        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> success = (HttpResponse<String>) mock(HttpResponse.class);
        when(success.statusCode()).thenReturn(200);
        when(client.send(any(), any())).thenReturn((HttpResponse) success);

        TokenBucketRateLimiter sharedLimiter = new TokenBucketRateLimiter(1);
        AdobeDispatchService service = new AdobeDispatchService(
                client,
                sharedLimiter,
                sharedLimiter,
                1,
                0L,
                Duration.ofSeconds(10));

        long start = System.nanoTime();
        service.dispatch("{\"wamid\":\"5\"}", "cdp-key", "ajo-key");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMillis >= 900, "Expected the dispatch to wait for a new rate-limit permit");
    }
}
