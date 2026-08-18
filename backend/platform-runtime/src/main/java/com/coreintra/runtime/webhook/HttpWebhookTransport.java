package com.coreintra.runtime.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * {@code HttpURLConnection}, because it is in the JDK.
 *
 * <p>An on-premises box may have no outbound internet and no appetite for
 * another HTTP client on the classpath. The JDK client is unfashionable and
 * entirely sufficient for a POST with two headers.
 *
 * <p>Both timeouts are set. A webhook endpoint that accepts a connection and
 * then never answers would otherwise hold a dispatcher thread until the JVM
 * restarts, and one slow subscriber would become every subscriber's outage.
 */
@Service
public class HttpWebhookTransport implements WebhookTransport {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public HttpWebhookTransport(
            @Value("${coreintra.webhooks.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${coreintra.webhooks.read-timeout-ms:10000}") int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public Result post(String url, String payload, String signatureHeader) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty(WebhookSigner.HEADER, signatureHeader);
            byte[] body = payload.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body);
                out.flush();
            } finally {
                out.close();
            }
            return Result.of(connection.getResponseCode());
        } catch (IOException unreachable) {
            return Result.failed(unreachable.getClass().getSimpleName()
                    + ": " + unreachable.getMessage());
        } catch (RuntimeException misconfigured) {
            // A malformed URL that survived the CHECK constraint, for instance.
            return Result.failed(misconfigured.getClass().getSimpleName()
                    + ": " + misconfigured.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
