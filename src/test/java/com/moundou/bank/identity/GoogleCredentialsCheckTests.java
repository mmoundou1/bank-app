package com.moundou.bank.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast suite. The startup warning for missing or mangled Google credentials. */
class GoogleCredentialsCheckTests {

    @Test
    void realLookingCredentialsRaiseNothing() {
        assertThat(new GoogleCredentialsCheck("123-abc.apps.googleusercontent.com", "GOCSPX-secret").problems()).isEmpty();
    }

    @Test
    void missingCredentialsAreNamed() {
        assertThat(new GoogleCredentialsCheck("unset", "").problems()).containsExactly(
                "GOOGLE_CLIENT_ID is not set in the environment",
                "GOOGLE_CLIENT_SECRET is not set in the environment");
    }

    @Test
    void strayWhitespaceFromCopyingIsCaught() {
        assertThat(new GoogleCredentialsCheck("123-abc.apps.googleusercontent.com\n", " GOCSPX-secret").problems())
                .containsExactly(
                        "GOOGLE_CLIENT_ID has spaces or line breaks at its start or end",
                        "GOOGLE_CLIENT_SECRET has spaces or line breaks at its start or end");
    }

    @Test
    void theWarningNeverContainsTheValues() {
        assertThat(new GoogleCredentialsCheck(" leaked-id ", " leaked-secret ").problems())
                .noneMatch(p -> p.contains("leaked"));
    }
}
