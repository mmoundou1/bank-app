package com.moundou.bank.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keep-alive endpoint for ADR-011.
 *
 * The scheduled ping touches the database deliberately: Neon's compute idles
 * independently of Render's, so pinging the application alone would leave a
 * database wake on the critical path and reintroduce the latency ADR-011 removes.
 *
 * It always returns 200. This is a warmth probe, not a liveness gate - a database
 * outage must not stop the pings that keep the instance alive, and AVL-2 requires
 * the application to stay readable while degraded.
 *
 * Unauthenticated by design. It exposes no ledger data, so Auth.Login-2 is unaffected.
 */
@RestController
public class HealthController {

    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    public HealthController(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "up");
        body.put("database", probeDatabase());
        return ResponseEntity.ok(body);
    }

    private String probeDatabase() {
        JdbcTemplate template = jdbcTemplate.getIfAvailable();
        if (template == null) {
            return "unconfigured";
        }
        try {
            template.queryForObject("SELECT 1", Integer.class);
            return "reachable";
        } catch (Exception e) {
            return "unreachable";
        }
    }
}
