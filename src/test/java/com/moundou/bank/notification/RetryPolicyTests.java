package com.moundou.bank.notification;

import com.moundou.bank.notification.RetryPolicy.Next.GiveUp;
import com.moundou.bank.notification.RetryPolicy.Next.RetryAfter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. How long the outbox worker waits after a failed send, and when it gives up
 * (Technical Design 7; ADR-010: retries are bounded so a rejecting provider cannot burn
 * the free allowance). This is the specification for RetryPolicy.afterFailedAttempt.
 *
 * {@code attempts} counts every send tried so far, including the one that just failed.
 * The first send happens as soon as the alert is due. An alert is sent at most five times
 * in all (decided in MB-18), so there are four waits between sends: 1 minute, 5 minutes,
 * 30 minutes and 2 hours. After the fifth failed send it is marked failed.
 */
class RetryPolicyTests {

    @Test
    void afterTheFirstFailedSendItTriesAgainInOneMinute() {
        assertThat(RetryPolicy.afterFailedAttempt(1)).isEqualTo(new RetryAfter(Duration.ofMinutes(1)));
    }

    @Test
    void eachRetryWaitsLongerThanTheOneBefore() {
        assertThat(RetryPolicy.afterFailedAttempt(2)).isEqualTo(new RetryAfter(Duration.ofMinutes(5)));
        assertThat(RetryPolicy.afterFailedAttempt(3)).isEqualTo(new RetryAfter(Duration.ofMinutes(30)));
        assertThat(RetryPolicy.afterFailedAttempt(4)).isEqualTo(new RetryAfter(Duration.ofHours(2)));
    }

    @Test
    void afterTheFifthFailedSendItGivesUp() {
        assertThat(RetryPolicy.afterFailedAttempt(5)).isEqualTo(new GiveUp());
    }

    @Test
    void anAlertPastTheLimitAlsoGivesUp() {
        // A crash between claiming an alert and recording the result lets the reservation
        // run out, so the alert can be claimed, and counted, once more than planned. It
        // must still give up, not ask for a fifth wait that does not exist.
        for (int attempts = 6; attempts <= 10; attempts++) {
            assertThat(RetryPolicy.afterFailedAttempt(attempts)).as("attempts " + attempts).isEqualTo(new GiveUp());
        }
    }
}
