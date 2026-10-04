package com.moundou.bank.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
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
                .query(MemberDirectory::member)
                .optional();
    }

    /**
     * Who {@code memberId} can choose as the other party on a new entry: every active
     * member except themselves, in name order (Ledger.Entry-1, -3; Auth.Roles-4).
     *
     * <p>Leaving out yourself and deactivated members here only tidies the dropdown.
     * EntryValidator still refuses both, because a request can be sent without the form.
     */
    public List<Member> counterpartiesFor(UUID memberId) {
        return jdbc.sql("""
                SELECT member_id, display_name, time_zone, status
                  FROM member
                 WHERE status = 'active'
                   AND member_id <> :me
                 ORDER BY lower(display_name), member_id""")
                .param("me", memberId)
                .query(MemberDirectory::member)
                .list();
    }

    /** Turns one row of the queries above into a {@link Member}. */
    private static Member member(ResultSet rs, int rowNum) throws SQLException {
        return new Member(
                rs.getObject("member_id", UUID.class),
                rs.getString("display_name"),
                ZoneId.of(rs.getString("time_zone")),
                "active".equals(rs.getString("status")));
    }
}
