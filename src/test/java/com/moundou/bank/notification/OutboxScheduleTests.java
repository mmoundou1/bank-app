package com.moundou.bank.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Fast suite. How the outbox worker is switched on (MB-18, Technical Design 7): a 15-second
 * timer that exists only when bank.notification.worker-enabled is on, and the settings in
 * the app's own application.yml.
 */
class OutboxScheduleTests {

    private final ApplicationContextRunner app = new ApplicationContextRunner()
            .withUserConfiguration(OutboxSchedule.class)
            .withBean(OutboxWorker.class, () -> mock(OutboxWorker.class));

    @Test
    void whenEnabledEachTickRunsOneVisit() {
        app.withPropertyValues("bank.notification.worker-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OutboxSchedule.class);

            context.getBean(OutboxSchedule.class).sendDueAlerts();

            verify(context.getBean(OutboxWorker.class)).runOnce();
        });
    }

    @Test
    void whenDisabledOrUnsetThereIsNoTimer() {
        app.withPropertyValues("bank.notification.worker-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxSchedule.class));
        app.run(context -> assertThat(context).doesNotHaveBean(OutboxSchedule.class));
    }

    @Test
    void theAppItselfRunsTheWorker() {
        // Tests switch it off in src/test/resources; the shipped settings must not.
        assertThat(mainSettings().getProperty("bank.notification.worker-enabled")).isEqualTo("true");
    }

    @Test
    void theSendTimeoutLeavesEveryAlertInAVisitInsideItsReservation() {
        // OutboxWorker claims up to 20 alerts and reserves them for 10 minutes. If every
        // send in a visit hung until the timeout, the last one must still start before the
        // reservation runs out, or another visit could claim it and send it twice.
        Duration timeout = DurationStyle.detectAndParse(mainSettings().getProperty("bank.notification.brevo.timeout"));

        assertThat(timeout.multipliedBy(20)).isLessThan(Duration.ofMinutes(10));
    }

    private static Properties mainSettings() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }
}
