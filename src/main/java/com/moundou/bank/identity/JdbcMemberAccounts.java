package com.moundou.bank.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcMemberAccounts implements MemberAccounts {

    private final JdbcClient jdbc;

    JdbcMemberAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MemberAccount> findBySubject(String subject) {
        return jdbc.sql("""
                SELECT member_id, display_name, time_zone, role, status
                  FROM member
                 WHERE idp_subject = :subject""")
                .param("subject", subject)
                .query(JdbcMemberAccounts::mapRow)
                .optional();
    }

    /**
     * Inserts the member with a new application-generated id (ADR-015). Status and
     * created_at take their defaults: active, now.
     */
    @Override
    public MemberAccount create(NewMemberAccount account) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO member (member_id, idp_subject, email, display_name, time_zone, role)
                VALUES (:id, :subject, :email, :name, :zone, :role)""")
                .param("id", id)
                .param("subject", account.subject())
                .param("email", account.email())
                .param("name", account.displayName())
                .param("zone", account.timeZone().getId())
                .param("role", account.role().dbValue())
                .update();
        return new MemberAccount(id, account.displayName(), account.timeZone(), account.role(), true);
    }

    private static MemberAccount mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MemberAccount(
                rs.getObject("member_id", UUID.class),
                rs.getString("display_name"),
                ZoneId.of(rs.getString("time_zone")),
                Role.fromDb(rs.getString("role")),
                "active".equals(rs.getString("status")));
    }
}
