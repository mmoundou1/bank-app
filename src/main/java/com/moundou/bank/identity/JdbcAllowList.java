package com.moundou.bank.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAllowList implements AllowList {

    private final JdbcClient jdbc;

    JdbcAllowList(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean contains(String email) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM allowed_email WHERE email = :email)")
                .param("email", email)
                .query(Boolean.class)
                .single();
    }
}
