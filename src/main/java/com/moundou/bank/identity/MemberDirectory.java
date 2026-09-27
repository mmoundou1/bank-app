package com.moundou.bank.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to members for other modules (Technical Design 2: no module reaches into
 * another's tables). The ledger asks here who a member is, what their time zone is, and
 * whether they are active; it never queries {@code member} itself.
 */
@Repository
public class MemberDirectory {

    private final JdbcClient jdbc;

    public MemberDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Member> findById(UUID id) {
        return jdbc.sql("""
                SELECT member_id, display_name, time_zone, status
                  FROM member
                 WHERE member_id = :id""")
                .param("id", id)
                .query((rs, n) -> new Member(
                        rs.getObject("member_id", UUID.class),
                        rs.getString("display_name"),
                        ZoneId.of(rs.getString("time_zone")),
                        "active".equals(rs.getString("status"))))
                .optional();
    }
}
