package com.moundou.bank.ops;

import com.moundou.bank.support.TestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. The real startup path: Spring Boot's Flyway auto-configuration, through
 * {@link StartupMigration}, applies every migration to an empty database when the
 * application starts (ADR-015, MB-7 done-when). The other slow tests call Flyway
 * directly; this one proves the wiring.
 */
@SpringBootTest
class MigrationOnStartupIT {

    @DynamicPropertySource
    static void emptyDatabase(DynamicPropertyRegistry registry) {
        Flyway.configure().dataSource(TestDatabase.dataSource()).cleanDisabled(false).load().clean();
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @Autowired
    DataSource dataSource;

    @Test
    void startupAppliesEveryMigration() {
        List<String> applied = JdbcClient.create(dataSource)
                .sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list();
        assertThat(applied).containsExactly("1", "2");
    }
}
