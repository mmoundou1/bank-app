package com.moundou.bank.identity;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast suite. Profile and email rules (Auth.Roles-6, -8, -11). */
class ProfileRulesTests {

    @Test
    void aNameAndARealZoneAreAccepted() {   // T-Roles-11a (the rule half)
        assertThat(ProfileRules.validate("  Maman ", "Africa/Douala"))
                .isEqualTo(new ProfileRules.Result.Valid(new ProfileRules.Profile("Maman", ZoneId.of("Africa/Douala"))));
    }

    @Test
    void anUnknownZoneIsRejected() {   // T-Roles-11b
        assertThat(ProfileRules.validate("Maman", "Africa/Atlantis"))
                .isEqualTo(new ProfileRules.Result.Invalid(Map.of("timeZone", "profile.timeZone.unknown")));
        assertThat(ProfileRules.validate("Maman", null))
                .isEqualTo(new ProfileRules.Result.Invalid(Map.of("timeZone", "profile.timeZone.unknown")));
    }

    @Test
    void fixedOffsetsAreNotOfferedBecauseTheyIgnoreDaylightSaving() {
        assertThat(ProfileRules.ZONES).contains("Africa/Douala", "America/New_York", "Europe/Paris")
                .doesNotContain("UTC", "GMT", "Etc/GMT+1", "EST", "SystemV/EST5");
        assertThat(ProfileRules.validate("Maman", "Etc/GMT-1")).isInstanceOf(ProfileRules.Result.Invalid.class);
        assertThat(ProfileRules.ZONES).isSorted();
    }

    @Test
    void theDisplayNameIsRequiredAndBounded() {
        assertThat(ProfileRules.validate("   ", "Europe/Paris"))
                .isEqualTo(new ProfileRules.Result.Invalid(Map.of("displayName", "profile.displayName.required")));
        assertThat(ProfileRules.validate("x".repeat(81), "Europe/Paris"))
                .isEqualTo(new ProfileRules.Result.Invalid(Map.of("displayName", "profile.displayName.tooLong")));
        assertThat(ProfileRules.validate("x".repeat(80), "Europe/Paris")).isInstanceOf(ProfileRules.Result.Valid.class);
    }

    @Test
    void emailsAreStoredTrimmedAndLowerCase() {
        assertThat(EmailAddresses.normalize("  Ama.Moundou@Gmail.COM ")).contains("ama.moundou@gmail.com");
    }

    @Test
    void thingsThatCannotBeEmailsAreRejected() {
        for (String bad : new String[] {null, "", "ama", "ama@", "@gmail.com", "ama@gmail", "a b@gmail.com", "a@b@c.com"}) {
            assertThat(EmailAddresses.normalize(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }
}
