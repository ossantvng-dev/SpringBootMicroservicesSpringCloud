package com.photoapp.test.support;

import com.photoapp.test.support.security.TestJwt;
import com.photoapp.test.support.web.PhotoAppSecuritySliceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
// Boot 4 moved the MVC slice annotations out of
// org.springframework.boot.test.autoconfigure.web.servlet into this package.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE PHASE 1 GATE.
 *
 * <p>Everything in Phase 3 depends on slice tests loading the REAL security chain from
 * photo-app-security-lib. That library has no auto-configuration — applications pick it up with
 * {@code @ComponentScan("com.photoapp.security")}, and a slice test does not component-scan. If
 * {@link PhotoAppSecuritySliceConfig} ever stops taking effect, Spring Boot quietly substitutes
 * its DEFAULT security chain and every authorization assertion in the suite passes against rules
 * that are not the ones shipped. The suite would be green and worthless.
 *
 * <p>So this test asserts the chain is genuinely OURS, using properties the default chain does
 * not have:
 * <ul>
 *   <li>a {@code USER} token on an {@code ADMIN}-only endpoint is <b>403, not 401 and not 200</b>
 *       — this is the Step 8 defect, which returned 500 before the fix;</li>
 *   <li>the 403 body is the project's {@code ApiErrorDTO}, proving
 *       {@code GlobalExceptionHandler} is wired and its {@code AccessDeniedException} handler
 *       still sits above the {@code Exception} catch-all;</li>
 *   <li>the project's own headers are applied.</li>
 * </ul>
 *
 * <p>If this test fails, do not weaken it — fix the configuration. A failure here means the rest
 * of the suite cannot be trusted.
 */
@SpringBootTest(classes = SecuritySliceControlTest.TestApp.class)
@AutoConfigureMockMvc
@Import(PhotoAppSecuritySliceConfig.class)
class SecuritySliceControlTest {

    @Autowired
    private MockMvc mockMvc;

    @EnableAutoConfiguration
    @RestController
    @RequestMapping("/accounts")
    static class TestApp {
        /** Mirrors a real ADMIN-only endpoint, e.g. POST /accounts. */
        @GetMapping("/admin-only")
        @PreAuthorize("hasRole('ADMIN')")
        public String adminOnly() {
            return "reached";
        }

        /** Mirrors an endpoint open to either role. */
        @GetMapping("/either-role")
        @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
        public String eitherRole() {
            return "reached";
        }
    }

    @Test
    @DisplayName("no token is rejected with 401")
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/accounts/admin-only"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("THE GATE: a USER token on an ADMIN endpoint is 403, not 401, 200 or 500")
    void userTokenOnAdminEndpointIsForbidden() throws Exception {
        mockMvc.perform(get("/accounts/admin-only")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.userToken())))
                .andExpect(status().isForbidden())
                // The project's error shape, not Spring's default {timestamp,status,error,path}.
                .andExpect(jsonPath("$.httpStatus").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Forbidden"))
                .andExpect(jsonPath("$.path").value("/accounts/admin-only"))
                .andExpect(jsonPath("$.timeStamp").exists());
    }

    @Test
    @DisplayName("an ADMIN token reaches the ADMIN endpoint")
    void adminTokenReachesAdminEndpoint() throws Exception {
        mockMvc.perform(get("/accounts/admin-only")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.adminToken())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a USER token reaches an endpoint open to either role")
    void userTokenReachesEitherRoleEndpoint() throws Exception {
        mockMvc.perform(get("/accounts/either-role")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.userToken())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an expired token is rejected with 401 - proves the Clock seam mints real expiry")
    void expiredTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/accounts/either-role")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.expiredAdminToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token signed with the wrong key is rejected with 401")
    void wrongSignatureIsUnauthorized() throws Exception {
        mockMvc.perform(get("/accounts/either-role")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.tokenWithWrongSignature())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the project's security headers are applied - proves it is OUR chain")
    void projectSecurityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/accounts/admin-only")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.adminToken())))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String frameOptions = result.getResponse().getHeader("X-Frame-Options");
                    if (!"DENY".equals(frameOptions)) {
                        throw new AssertionError(
                                "X-Frame-Options was '" + frameOptions + "', expected DENY. "
                                        + "The default Spring Security chain is probably active "
                                        + "instead of SecurityConfiguration.");
                    }
                    String csp = result.getResponse().getHeader("Content-Security-Policy");
                    if (csp == null || !csp.contains("script-src 'self'")) {
                        throw new AssertionError(
                                "Content-Security-Policy was '" + csp + "', expected script-src 'self'.");
                    }
                });
    }

    @Test
    @DisplayName("the OpenAPI paths are anonymous, as configured")
    void openApiPathsArePermitted() throws Exception {
        /*
            The assertion is precisely "security did not reject it": anything other than 401 or
            403 means the permit rules are in place. Deliberately NOT asserting a specific
            success status - no handler is registered for this path in the cut-down app, and
            what an unmapped path returns is a separate question from whether it is permitted.

            (It currently returns 500, not 404, because GlobalExceptionHandler's Exception
            catch-all swallows the no-handler condition. That is logged in backlog.txt as its
            own finding - it is the Step 8 defect class again - and is not this test's business.)
         */
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.ALL))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError(
                                "/v3/api-docs was rejected by security with " + status
                                        + ". The OpenAPI permit rules are missing from the chain.");
                    }
                });
    }
}
