package com.moundou.bank.ops;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.internal.exception.FlywaySqlException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Fast suite. The startup migration rule: an unreachable database lets the application
 * start (AVL-2, Technical Design 10); any other migration failure still stops it.
 */
class StartupMigrationTests {

    @Test
    void aDatabaseThatRefusesConnectionsDoesNotStopStartup() {
        // A real Flyway against a port nothing listens on: the genuine failure, not a mock.
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:postgresql://127.0.0.1:1/none?connectTimeout=2", "nobody", "")
                .load();
        assertThatCode(() -> StartupMigration.migrate(flyway)).doesNotThrowAnyException();
    }

    @Test
    void aPoolTimeoutCountsAsUnreachable() {
        // What HikariCP throws when Neon does not answer within connection-timeout.
        Flyway flyway = mock(Flyway.class);
        doThrow(new FlywaySqlException("Unable to obtain connection from database",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")))
                .when(flyway).migrate();
        assertThatCode(() -> StartupMigration.migrate(flyway)).doesNotThrowAnyException();
    }

    @Test
    void aBrokenMigrationStillStopsStartup() {
        Flyway flyway = mock(Flyway.class);
        doThrow(new FlywayException("Validate failed: Migration checksum mismatch for migration version 1"))
                .when(flyway).migrate();
        assertThatThrownBy(() -> StartupMigration.migrate(flyway)).hasMessageContaining("checksum mismatch");
    }

    @Test
    void anSqlErrorInsideAConnectedSessionStillStopsStartup() {
        Flyway flyway = mock(Flyway.class);
        doThrow(new FlywaySqlException("Migration V3 failed",
                new SQLException("syntax error at or near \"CREAT\"", "42601")))
                .when(flyway).migrate();
        assertThatThrownBy(() -> StartupMigration.migrate(flyway)).isInstanceOf(FlywayException.class);
    }
}
