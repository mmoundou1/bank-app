package com.moundou.bank.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfig {

    @Bean
    SignInService signInService(AllowList allowList, MemberAccounts members, IdentityProperties properties) {
        return new SignInService(allowList, members, properties.defaultTimeZone());
    }
}
