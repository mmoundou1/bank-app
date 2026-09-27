package com.moundou.bank.identity;

import com.moundou.bank.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slow suite. The database side of sign-in against real Postgres: the allow-list and
 * member lookups SignInService depends on, and sessions stored in Postgres so they
 * survive a restart (Auth.Login-4, -6; Technical Design D4).
 */
@SpringBootTest
class SignInPersistenceIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.migrateFromEmpty();
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @Autowired AllowList allowList;
    @Autowired MemberAccounts members;
    @Autowired SessionRepository<? extends Session> sessions;

    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc = JdbcClient.create(TestDatabase.dataSource());
        jdbc.sql("TRUNCATE notification, transaction_status_history, transaction, allowed_email, member, spring_session CASCADE").update();
    }

    @Test
    void theAllowListMatchesExactlyTheStoredAddress() {
        jdbc.sql("INSERT INTO allowed_email (email) VALUES ('ama@example.com')").update();
        assertThat(allowList.contains("ama@example.com")).isTrue();
        assertThat(allowList.contains("ben@example.com")).isFalse();
    }

    @Test
    void aCreatedMemberIsFoundAgainByGoogleSubject() {
        MemberAccount created = members.create(new NewMemberAccount(
                "sub-ama", "ama@example.com", "Ama", ZoneId.of("Africa/Douala"), Role.FAMILY_MEMBER));

        assertThat(members.findBySubject("sub-ama")).contains(created);
        assertThat(created.active()).isTrue();
        assertThat(members.findBySubject("sub-nobody")).isEmpty();

        // What was stored is what the rest of the app reads (Auth.Roles-1, CON-8, CON-9).
        var row = jdbc.sql("SELECT email, role, status, time_zone FROM member WHERE member_id = :id")
                .param("id", created.id())
                .query((rs, i) -> rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3) + " " + rs.getString(4))
                .single();
        assertThat(row).isEqualTo("ama@example.com family_member active Africa/Douala");
    }

    @Test
    void aDeactivatedMemberIsReadAsInactive() {
        MemberAccount created = members.create(new NewMemberAccount(
                "sub-ben", "ben@example.com", "Ben", ZoneId.of("Europe/Paris"), Role.FAMILY_MEMBER));
        jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :id").param("id", created.id()).update();

        assertThat(members.findBySubject("sub-ben")).hasValueSatisfying(m -> assertThat(m.active()).isFalse());
    }

    /** Auth.Login-4: sessions are rows in Postgres, not memory, so a restart keeps them. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void sessionsAreStoredInPostgresForThirtyDays() {
        assertThat(sessions).isInstanceOf(JdbcIndexedSessionRepository.class);

        SessionRepository repository = sessions;
        Session session = repository.createSession();
        session.setAttribute("probe", "kept");
        repository.save(session);

        assertThat(jdbc.sql("SELECT count(*) FROM spring_session").query(Integer.class).single()).isOne();
        Session reread = repository.findById(session.getId());
        assertThat((String) reread.getAttribute("probe")).isEqualTo("kept");
        assertThat(reread.getMaxInactiveInterval()).isEqualTo(Duration.ofDays(30));   // Auth.Login-6
    }
}
