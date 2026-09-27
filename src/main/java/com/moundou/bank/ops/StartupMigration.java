package com.moundou.bank.ops;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * How Flyway migrations run on startup (ADR-015), reconciled with Technical Design 10
 * and AVL-2: startup must not fail just because Neon is unreachable.
 *
 * <ul>
 *   <li><b>Database unreachable:</b> log an error and let the application start
 *       anyway, so it can answer the keep-alive ping and serve the degraded mode of
 *       Dashboard.Net-5. Migrations are applied at the next startup that reaches the
 *       database. Usually nothing is pending: a plain restart finds the schema already
 *       current.</li>
 *   <li><b>Database reachable but a migration fails</b> (bad SQL, a merged file that
 *       was edited, a checksum mismatch): startup fails as Flyway normally makes it.
 *       Serving the ledger against a schema in an unknown state is worse than being
 *       down, and on Render a failed deploy leaves the previous version serving.</li>
 * </ul>
 *
 * Decided by the project lead on 2026-09-27 while implementing MB-7.
 */
@Configuration(proxyBeanMethods = false)
public class StartupMigration {

    private static final Logger log = LoggerFactory.getLogger(StartupMigration.class);

    @Bean
    FlywayMigrationStrategy tolerateUnreachableDatabase() {
        return StartupMigration::migrate;
    }

    static void migrate(Flyway flyway) {
        try {
            flyway.migrate();
        } catch (FlywayException e) {
            if (!isUnreachable(e)) {
                throw e;
            }
            log.error("Database unreachable at startup; schema migrations NOT applied. Starting in "
                    + "degraded mode (AVL-2). Migrations will run on the next startup that reaches "
                    + "the database. Cause: {}", rootMessage(e));
        }
    }

    /**
     * True when the failure is failing to connect at all, as opposed to a failure inside
     * a connected session. SQLState class 08 is the SQL standard's "connection exception".
     */
    static boolean isUnreachable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException
                    || t instanceof ConnectException
                    || t instanceof UnknownHostException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
