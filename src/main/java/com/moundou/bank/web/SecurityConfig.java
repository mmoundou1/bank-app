package com.moundou.bank.web;

import com.moundou.bank.identity.GoogleSignIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Who may reach what (SRS 3.10; Technical Design 6).
 *
 * <ul>
 *   <li><b>Open to anyone:</b> {@code /healthz} (the keep-alive ping, ADR-011; it exposes
 *       no ledger data), the sign-in page, error pages and static assets.</li>
 *   <li><b>Everything else needs a signed-in member</b> (Auth.Login-1, -2). A request
 *       without one is sent to the sign-in page and reaches no controller, whatever the
 *       browser shows or hides.</li>
 *   <li><b>No registration route exists</b> (Auth.Login-3). The only way in is Google
 *       sign-in, gated by the allow-list.</li>
 * </ul>
 *
 * This is the outer wall only. Every service still checks who is acting (Technical
 * Design 6: authorization lives in the services, not the controllers).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, GoogleSignIn googleSignIn) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/healthz", "/login", "/error", "/css/**", "/favicon.ico").permitAll()
                // Administrators only (Auth.Roles-5). The role is the one granted at sign-in;
                // AdminService checks the database again on every call.
                .requestMatchers("/admin/**").hasRole("FAMILY_ADMINISTRATOR")
                .anyRequest().authenticated())
            .oauth2Login(login -> login
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(googleSignIn))
                .failureHandler(refusalHandler()))
            .logout(logout -> logout
                .logoutSuccessUrl("/login?signedOut"));
        // CSRF protection stays at Spring's default: every form POST carries a token.
        return http.build();
    }

    /**
     * Sends a refused sign-in to the sign-in page with the reason, so it can say why
     * (Auth.Login-5: "report that the account is no longer active"). Anything else that
     * goes wrong with Google gets a general message on the page.
     *
     * Every failure is also logged (SEC-5), with Spring's error code and description:
     * an expected refusal at INFO, anything else at WARN. Those fields name what went
     * wrong ("invalid_token_response", "authorization_request_not_found", ...) and never
     * contain tokens or secrets, so they are safe to log.
     */
    static AuthenticationFailureHandler refusalHandler() {
        return (request, response, exception) -> {
            String target = "/login?failed";
            if (exception instanceof OAuth2AuthenticationException oauth
                    && oauth.getError().getErrorCode().startsWith(GoogleSignIn.REFUSAL_PREFIX)) {
                String reason = oauth.getError().getErrorCode().substring(GoogleSignIn.REFUSAL_PREFIX.length());
                target = "/login?refused=" + reason;
                log.info("Sign-in refused: {}", reason);
            } else if (exception instanceof OAuth2AuthenticationException oauth) {
                log.warn("Sign-in with Google failed: [{}] {}",
                        oauth.getError().getErrorCode(), oauth.getError().getDescription());
            } else {
                log.warn("Sign-in with Google failed: {}: {}",
                        exception.getClass().getSimpleName(), exception.getMessage());
            }
            response.sendRedirect(request.getContextPath() + target);
        };
    }
}
