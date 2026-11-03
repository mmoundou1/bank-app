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
 * The first send happens as soon as the alert is due. Technical Design 7 then allows five
 * retries, waiting 1 minute, 5 minutes, 30 minutes, 2 hours and 6 hours, so an alert is
 * tried at most six times. After the sixth failed send it is marked failed.
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
        assertThat(RetryPolicy.afterFailedAttempt(5)).isEqualTo(new RetryAfter(Duration.ofHours(6)));
    }

    @Test
    void afterTheSixthFailedSendItGivesUp() {
        assertThat(RetryPolicy.afterFailedAttempt(6)).isEqualTo(new GiveUp());
    }

    @Test
    void anAlertPastTheLimitAlsoGivesUp() {
        // A crash between claiming an alert and recording the result lets the reservation
        // run out, so the alert can be claimed, and counted, once more than planned. It
        // must still give up, not ask for a seventh wait that does not exist.
        for (int attempts = 7; attempts <= 10; attempts++) {
            assertThat(RetryPolicy.afterFailedAttempt(attempts)).as("attempts " + attempts).isEqualTo(new GiveUp());
        }
    }
}
