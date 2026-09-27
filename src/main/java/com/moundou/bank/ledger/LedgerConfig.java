package com.moundou.bank.ledger;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerProperties.class)
class LedgerConfig {

    /**
     * The one source of "now". Always UTC: each member's own zone is applied where a
     * date is needed (CON-8), never the server's. Tests replace this bean to control time.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EntryValidator entryValidator(Clock clock, LedgerProperties properties) {
        return new EntryValidator(clock, properties.onboardingEndsOn());
    }
}
