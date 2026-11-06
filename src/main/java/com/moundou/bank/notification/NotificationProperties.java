package com.moundou.bank.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Settings for the alert emails (Technical Design 7, 10). Values and their environment
 * variables are in application.yml.
 *
 * @param baseUrl       where the app is reached, such as {@code https://bank-app.onrender.com};
 *                      every link in an alert starts with it (Notify.Pending-2). Taken from
 *                      {@code BASE_URL}, or else from {@code RENDER_EXTERNAL_URL}, which
 *                      Render sets on every web service. Blank when neither is set, and
 *                      then no alert can be composed: they fail, are retried and logged.
 * @param workerEnabled whether the outbox worker runs every 15 seconds. On in the app, off
 *                      in tests, so tests that start the whole app never send alerts.
 * @param brevo         the email provider (MB-18)
 */
@ConfigurationProperties("bank.notification")
record NotificationProperties(String baseUrl, boolean workerEnabled, Brevo brevo) {

    /**
     * @param url         Brevo's API
     * @param apiKey      from {@code BREVO_API_KEY}; a secret, set only in Render's
     *                    environment settings
     * @param senderEmail the From address, verified in Brevo: {@code ALERT_SENDER_EMAIL}
     * @param timeout     the longest one request may take (see BrevoEmailGateway)
     */
    record Brevo(URI url, String apiKey, String senderEmail, Duration timeout) {
    }
}
