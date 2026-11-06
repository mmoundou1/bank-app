package com.moundou.bank.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the alert emails (Technical Design 7, 10).
 *
 * @param baseUrl where the app is reached, such as {@code https://bank-app.onrender.com};
 *                every link in an alert starts with it (Notify.Pending-2). Taken from
 *                {@code BASE_URL}, or else from {@code RENDER_EXTERNAL_URL}, which Render
 *                sets on every web service. Blank when neither is set, and then no alert
 *                can be composed: they fail and are retried, and the worker logs why.
 */
@ConfigurationProperties("bank.notification")
record NotificationProperties(String baseUrl) {
}
