package com.moundou.bank.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * Identity settings, bound from {@code bank.identity.*}.
 *
 * @param defaultTimeZone the zone a member gets on first sign-in (Technical Design 6),
 *                        until it is changed to where they actually live (CON-8)
 */
@ConfigurationProperties("bank.identity")
public record IdentityProperties(ZoneId defaultTimeZone) {
}
