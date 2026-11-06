package com.moundou.bank.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The notification module's settings. The outbox worker is not a bean yet: it is switched
 * on, with its 15-second schedule, once a real EmailGateway exists (MB-18).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfig {
}
