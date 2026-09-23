package com.moundou.bank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast suite. No container, no database. Runs on every commit.
 *
 * DataSourceAutoConfiguration is excluded here rather than in application.yml:
 * production must connect to Neon, but this test's purpose is to prove the
 * health endpoint answers when no database exists at all - and CI has no
 * DATABASE_URL to resolve.
 */
@SpringBootTest(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
class HealthEndpointTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointRespondsWithoutADatabase() throws Exception {
        mockMvc.perform(get("/healthz"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("up"));
    }
}
