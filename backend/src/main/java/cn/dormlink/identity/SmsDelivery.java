package cn.dormlink.identity;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class SmsDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(SmsDelivery.class);
    private final URI gateway;
    private final String token;
    private final JsonMapper json;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ThreadPoolExecutor deliveries =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    Thread.ofPlatform().daemon().name("auth-sms-", 0).factory());

    SmsDelivery(
            @Value("${APP_SMS_GATEWAY_URL:}") String url,
            @Value("${APP_SMS_GATEWAY_TOKEN:}") String token,
            @Value("${APP_SMS_SIGNING_KEY:}") String signingKey,
            JsonMapper json) {
        this.gateway = url.isBlank() ? null : URI.create(url);
        if (gateway != null
                && (!"https".equalsIgnoreCase(gateway.getScheme())
                        || gateway.getHost() == null
                        || gateway.getUserInfo() != null
                        || gateway.getFragment() != null
                        || signingKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                                < 32)) {
            throw new IllegalArgumentException(
                    "SMS requires an HTTPS gateway and a 32-byte signing key");
        }
        this.token = token;
        this.json = json;
    }

    public void enqueue(String phone, String purpose, String code) {
        if (gateway == null) {
            return;
        }
        try {
            deliveries.execute(() -> deliver(phone, purpose, code));
        } catch (RejectedExecutionException e) {
            LOG.warn("SMS delivery queue is full");
        }
    }

    private void deliver(String phone, String purpose, String code) {
        try {
            var request =
                    HttpRequest.newBuilder(gateway)
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "phone", phone, "purpose", purpose,
                                                            "code", code))));
            if (!token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            int status =
                    client.send(request.build(), HttpResponse.BodyHandlers.discarding())
                            .statusCode();
            if (status < 200 || status >= 300) {
                LOG.warn("SMS gateway rejected delivery, status {}", status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.warn("SMS delivery did not complete");
        }
    }

    @PreDestroy
    void close() {
        deliveries.shutdownNow();
    }
}
