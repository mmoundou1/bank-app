package com.moundou.bank.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for administration and profiles (MB-10). Kept apart from
 * {@link MemberAccounts} and {@link AllowList}, which serve sign-in only and have
 * in-memory stand-ins in SignInServiceTests.
 */
@Repository
public class MemberAdministration {

    /** A member as the administration page lists them. */
    public record MemberSummary(UUID id, String displayName, String email, Role role, boolean active, ZoneId timeZone) { }

    /** An allow-list entry, with who added it (null for the first, bootstrap entry). */
    public record AllowedEmail(String email, Instant addedAt, String addedByName) { }

    private final JdbcClient jdbc;

    public MemberAdministration(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- members --------------------------------------------------------------------

    public Optional<MemberSummary> findById(UUID id) {
        return jdbc.sql("SELECT member_id, display_name, email, role, status, time_zone FROM member WHERE member_id = :id")
                .param("id", id).query(MemberAdministration::mapMember).optional();
    }

    public List<MemberSummary> listMembers() {
        return jdbc.sql("""
                SELECT member_id, display_name, email, role, status, time_zone
                  FROM member ORDER BY status, lower(display_name)""")
                .query(MemberAdministration::mapMember).list();
    }

    /** Sets the member deactivated. Returns false if they already were, or do not exist. */
    public boolean deactivate(UUID id) {
        return jdbc.sql("UPDATE member SET status = 'deactivated' WHERE member_id = :id AND status = 'active'")
                .param("id", id).update() == 1;
    }

    public void updateProfile(UUID id, String displayName, ZoneId timeZone) {
        jdbc.sql("UPDATE member SET display_name = :name, time_zone = :zone WHERE member_id = :id")
                .param("name", displayName).param("zone", timeZone.getId()).param("id", id).update();
    }

    /**
     * The administrators who can actually administer: active, and with their address on
     * the allow-list so they can still sign in (Auth.Roles-12). Their rows are locked
     * until the surrounding transaction ends, always in member_id order.
     *
     * Why lock: two administrators deactivating each other at the same moment would each
     * see the other still active, both succeed, and leave none. With the lock the second
     * waits for the first to commit, then re-reads the rows and sees one administrator
     * fewer (Postgres re-checks the WHERE clause of a locked row after the wait).
     *
     * Known limit: the address checked is the one stored when the member first signed
     * in. If an administrator later changes the address on their Google account, the
     * stored one goes stale and they stop counting here, which errs on the side of
     * refusing.
     */
    public List<UUID> lockUsableAdministrators() {
        return jdbc.sql("""
                SELECT m.member_id FROM member m
                 WHERE m.role = 'family_administrator' AND m.status = 'active'
                   AND EXISTS (SELECT 1 FROM allowed_email a WHERE a.email = m.email)
                 ORDER BY m.member_id
                   FOR UPDATE OF m""")
                .query(UUID.class).list();
    }

    // ---- allow-list -----------------------------------------------------------------

    public List<AllowedEmail> listAllowed() {
        return jdbc.sql("""
                SELECT a.email, a.added_at, m.display_name
                  FROM allowed_email a LEFT JOIN member m ON m.member_id = a.added_by
                 ORDER BY a.email""")
                .query((rs, n) -> new AllowedEmail(rs.getString(1),
                        rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getString(3)))
                .list();
    }

    /** Returns false if the address was already listed. */
    public boolean allow(String email, UUID addedBy) {
        return jdbc.sql("""
                INSERT INTO allowed_email (email, added_by) VALUES (:email, :by)
                ON CONFLICT (email) DO NOTHING""")
                .param("email", email).param("by", addedBy).update() == 1;
    }

    /** Returns false if the address was not listed. Deletes nothing else (Data-7). */
    public boolean disallow(String email) {
        return jdbc.sql("DELETE FROM allowed_email WHERE email = :email").param("email", email).update() == 1;
    }

    /** Members whose stored address is this one: normally one, or none. */
    public List<UUID> membersWithEmail(String email) {
        return jdbc.sql("SELECT member_id FROM member WHERE email = :email").param("email", email).query(UUID.class).list();
    }

    private static MemberSummary mapMember(ResultSet rs, int rowNum) throws SQLException {
        return new MemberSummary(
                rs.getObject("member_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("email"),
                Role.fromDb(rs.getString("role")),
                "active".equals(rs.getString("status")),
                ZoneId.of(rs.getString("time_zone")));
    }
}
