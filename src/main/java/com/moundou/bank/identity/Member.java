package com.moundou.bank.identity;

import java.time.ZoneId;
import java.util.UUID;

/**
 * A family account as other modules see it (SRS 4.2). Deliberately no email or
 * identity-provider subject: nothing outside {@code identity} needs them.
 *
 * {@code timeZone} is the member's configured IANA zone (CON-8, ADR-012). It decides
 * what "today" means for everything this member records.
 */
public record Member(UUID id, String displayName, ZoneId timeZone, boolean active) {
}
