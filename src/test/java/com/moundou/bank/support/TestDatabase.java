package com.moundou.bank.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * The Postgres the slow suite runs against.
 *
 * By default, a Testcontainers Postgres 16, as in CI (ADR-009). If the environment
 * variable {@code IT_DATABASE_URL} is set, that server is used instead, for machines
 * with Postgres but no container runtime:
 *
 * <pre>
 *   IT_DATABASE_URL='jdbc:postgresql://localhost:5432/bank_it?user=bank' \
 *     mvn verify -DskipUnitTests=true
 * </pre>
 *
 * The database it names is wiped by {@link #migrateFromEmpty()}. Never point it at
 * anything that matters.
 */
public final class TestDatabase {

    private static DataSource dataSource;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private TestDatabase() { }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            String external = System.getenv("IT_DATABASE_URL");
            if (external != null && !external.isBlank()) {
                jdbcUrl = external;
            } else {
                PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
                container.start();   // stopped by Testcontainers' reaper when the JVM exits
                jdbcUrl = container.getJdbcUrl();
                username = container.getUsername();
                password = container.getPassword();
            }
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(6);   // enough for the concurrency tests' parallel transactions
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    public static String jdbcUrl() {
        dataSource();
        return jdbcUrl;
    }

    public static String username() {
        dataSource();
        return username;
    }

    public static String password() {
        dataSource();
        return password;
    }

    /**
     * Drops everything in the database and applies every migration from scratch, the
     * way a new Neon database would get them. Each test class starts here, so the
     * migrations are proven against an empty database on every run.
     */
    public static void migrateFromEmpty() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .cleanDisabled(false)   // test only; production keeps Flyway's default of true
                .load();
        flyway.clean();
        flyway.migrate();
    }
}
