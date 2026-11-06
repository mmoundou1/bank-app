package com.moundou.bank.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Sends alert emails through Brevo's transactional email API (ADR-010; chosen in MB-18
 * because, alone among the free providers without a card, it can send without the family
 * owning a domain).
 *
 * HTTPS only: Render's free web services cannot open SMTP ports (25, 465, 587) since
 * September 2025. One request per email: {@code POST /v3/smtp/email}, authenticated by the
 * {@code api-key} header, answered with 201.
 *
 * Any failure throws, and the worker records it as a failed try. The message says what
 * went wrong, for {@code last_error} and the log, and never contains the key.
 *
 * The timeout bounds every request. It must stay well under the worker's reservation
 * divided by its batch size (10 minutes / 20 alerts = 30 s): if every send in a batch
 * hung until the timeout, the last alerts would otherwise outlive their reservation and
 * could be claimed and sent twice.
 *
 * Specified by BrevoEmailGatewayTests.
 */
final class BrevoEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailGateway.class);
    private static final int MAX_ERROR_LENGTH = 300;

    private final RestClient http;
    private final String apiKey;
    private final String senderEmail;
    private final String senderName;

    BrevoEmailGateway(URI url, String apiKey, String senderEmail, String senderName, Duration timeout) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.senderEmail = senderEmail == null ? "" : senderEmail.strip();
        this.senderName = senderName;

        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requests.setReadTimeout(timeout);
        // Buffered, so each request carries a Content-Length instead of a chunked body: an
        // email is a few kilobytes, and a body of known length is what any server reads.
        this.http = RestClient.builder()
                .baseUrl(url.toString())
                .requestFactory(new BufferingClientHttpRequestFactory(requests))
                .build();

        if (this.apiKey.isEmpty() || this.senderEmail.isEmpty()) {
            log.warn("Alert emails cannot be sent until BREVO_API_KEY and ALERT_SENDER_EMAIL are both set");
        }
    }

    @Override
    public void send(EmailMessage message) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("BREVO_API_KEY is not set, so no alert can be sent");
        }
        if (senderEmail.isEmpty()) {
            throw new IllegalStateException("ALERT_SENDER_EMAIL is not set, so no alert can be sent");
        }

        BrevoEmail email = new BrevoEmail(
                new Sender(senderName, senderEmail),
                List.of(new Recipient(message.to())),
                message.subject(),
                message.body());
        try {
            http.post()
                    .uri("/v3/smtp/email")
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(email)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException refused) {
            throw new IllegalStateException("Brevo refused the email: " + refused.getStatusCode().value() + " "
                    + shorten(refused.getResponseBodyAsString()));
        } catch (ResourceAccessException unreachable) {
            throw new IllegalStateException("Brevo could not be reached: " + shorten(unreachable.getMessage()));
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH) + "...";
    }

    /** The request body Brevo expects; Jackson writes it as JSON. */
    record BrevoEmail(Sender sender, List<Recipient> to, String subject, String htmlContent) {
    }

    record Sender(String name, String email) {
    }

    record Recipient(String email) {
    }
}
