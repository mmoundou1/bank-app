package com.moundou.bank.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Says loudly at startup when the Google sign-in credentials are missing.
 *
 * application.yml lets the app start without them (GOOGLE_CLIENT_ID and
 * GOOGLE_CLIENT_SECRET default to "unset") so it can deploy before they exist. The cost
 * of that default is silence: a misnamed variable (CLIENT_ID instead of
 * GOOGLE_CLIENT_ID, say) leaves the app sending "unset" to Google, and the only
 * symptom is Google's "401 invalid_client". This turns that into one line in the log.
 */
@Component
class GoogleCredentialsCheck {

    private static final Logger log = LoggerFactory.getLogger(GoogleCredentialsCheck.class);

    static final String UNSET = "unset";

    private final String clientId;
    private final String clientSecret;

    GoogleCredentialsCheck(
            @Value("${spring.security.oauth2.client.registration.google.client-id:unset}") String clientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:unset}") String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnIfMissing() {
        for (String problem : problems()) {
            log.warn("Google sign-in will fail: {}", problem);
        }
    }

    /** What is wrong with the credentials, if anything. Never includes their values. */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        check("GOOGLE_CLIENT_ID", clientId, problems);
        check("GOOGLE_CLIENT_SECRET", clientSecret, problems);
        return problems;
    }

    private static void check(String variable, String value, List<String> problems) {
        if (value == null || value.isBlank() || UNSET.equals(value)) {
            problems.add(variable + " is not set in the environment");
        } else if (!value.equals(value.strip())) {
            problems.add(variable + " has spaces or line breaks at its start or end");
        }
    }
}
