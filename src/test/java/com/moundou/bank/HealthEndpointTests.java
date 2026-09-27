package com.moundou.bank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.moundou.bank.health.HealthController;
import com.moundou.bank.identity.GoogleSignIn;
import com.moundou.bank.web.SecurityConfig;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast suite. No container, no database. Runs on every commit.
 *
 * A web slice (@WebMvcTest) rather than the whole application: it loads the MVC layer
 * and this controller, with no DataSource at all. That is exactly the case this test
 * exists for - the health endpoint answering when no database exists - and it keeps
 * working now that the ledger has beans that need a database (MB-11).
 */
@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)   // the real security rules, which keep /healthz open (ADR-011)
class HealthEndpointTests {

    @MockBean
    GoogleSignIn googleSignIn;   // required by SecurityConfig; never called here


    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointRespondsWithoutADatabase() throws Exception {
        mockMvc.perform(get("/healthz"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("up"))
               .andExpect(jsonPath("$.database").value("unconfigured"));
    }
}
