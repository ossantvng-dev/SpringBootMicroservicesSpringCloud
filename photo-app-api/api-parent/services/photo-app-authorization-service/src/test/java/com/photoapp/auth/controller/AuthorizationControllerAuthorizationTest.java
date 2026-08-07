package com.photoapp.auth.controller;

import com.photoapp.auth.dto.AuthorizationResponseDTO;
import com.photoapp.auth.dto.LoginRequestDTO;
import com.photoapp.auth.dto.RefreshTokenRequestDTO;
import com.photoapp.auth.service.AuthorizationService;
import com.photoapp.auth.service.TokenHandlerService;
import com.photoapp.test.support.security.TestJwt;
import com.photoapp.test.support.web.ControllerEndpoints;
import com.photoapp.test.support.web.PhotoAppSecuritySliceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 for {@link AuthorizationController} — the inverse of the other four suites.
 *
 * <p>This controller has no {@code @PreAuthorize} anywhere and that is correct, not an
 * oversight: all three endpoints run BEFORE the caller holds a usable access token. Login
 * obviously has none; refresh is called precisely because the access token has expired or is
 * missing, so requiring one would deadlock the flow — the refresh token itself is the
 * credential, validated against the token store rather than the filter chain. Revoke follows
 * refresh for the same reason.
 *
 * <p>So the assertions run the opposite way round. Instead of proving requests are rejected, it
 * proves they are NOT: a missing token must not produce a 401, and — more subtly — neither
 * should a stale one, because {@code JwtFilter} inspects any {@code Authorization} header it
 * sees regardless of whether the path is permitted, and a client retrying login while an
 * interceptor still attaches its old token is an entirely ordinary situation.
 *
 * <p>That second case was broken until 2026-08-07 and
 * {@link #expiredTokensDoNotBlockAnyAuthEndpoint} is now its regression guard.
 *
 * <p>No Feign client is loaded. {@code CustomUserDetailsService} and the token services reach
 * the users service over Feign in production; here they are mocked, keeping this on the
 * authorization boundary and leaving the Feign interaction to Phase 4.
 */
@WebMvcTest
@ContextConfiguration(classes = AuthorizationControllerAuthorizationTest.SliceContext.class)
@Import({AuthorizationController.class, PhotoAppSecuritySliceConfig.class})
class AuthorizationControllerAuthorizationTest {

    /*
        Replaces PhotoAppAuthorizationServiceApplication as the context root - it carries
        @EnableFeignClients and @EntityScan, which a web slice cannot satisfy. Named explicitly
        because SpringBootContextLoader does not detect nested @Configuration classes.
     */
    @Configuration
    static class SliceContext {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthorizationService authorizationService;

    @MockitoBean
    private TokenHandlerService tokenHandlerService;

    private static final String LOGIN_BODY = """
            {"username":"ada","password":"correct-horse","codeVerifier":"pkce-verifier"}""";

    private static final String REFRESH_BODY = """
            {"refreshToken":"a-refresh-token"}""";

    private static AuthorizationResponseDTO aTokenPair() {
        return new AuthorizationResponseDTO("access", "refresh", "Bearer", 86_400L, 604_800L);
    }

    /**
     * Verifies all three {@code /auth} endpoints are reachable with no {@code Authorization}
     * header at all. This is the load-bearing property of the whole authentication flow: if the
     * {@code permitAll} rule for {@code /auth/**} were ever dropped, nobody could obtain a first
     * token and the entire system would be unreachable, with every other test in the project
     * still passing because they all mint their tokens directly.
     */
    @ParameterizedTest(name = "POST {0}")
    @CsvSource({"/auth/login", "/auth/refresh", "/auth/revoke"})
    void allAuthEndpointsAreReachableAnonymously(String path) throws Exception {
        stubEverything();

        String body = "/auth/login".equals(path) ? LOGIN_BODY : REFRESH_BODY;

        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("POST %s was rejected by security with %d, but /auth/** is permitAll "
                                + "and callers have no token yet by definition",
                                path, result.getResponse().getStatus())
                        .isNotIn(401, 403));
    }

    /**
     * REGRESSION GUARD for the defect fixed on 2026-08-07 — see backlog.txt, "expired token
     * locks a client out of /auth".
     *
     * <p>Verifies a stale expired token does not block any {@code /auth} endpoint. All three are
     * {@code permitAll} and all three used to answer 401 here: {@code JwtFilter} special-cased
     * {@code ExpiredJwtException} with {@code sendError(401)} and <em>returned without continuing
     * the chain</em>, so the permit rule was never consulted. Every other invalid-token shape
     * fell through the generic catch and proceeded, which is why
     * {@link #malformedTokensDoNotBlockLogin} passed throughout while this did not.
     *
     * <p>{@code /auth/refresh} is why it mattered. Its contract, written on
     * {@code TokenHandlerService#refreshToken}, says it must work with no Authorization header
     * because a client refreshes precisely when its access token has expired — and the filter
     * defeated that whenever the client also SENT the expired token, which is what essentially
     * every HTTP client interceptor does by default. The user was then wedged: refresh 401'd, and
     * so did the login they would retry instead.
     *
     * <p>The fix is that expiry is now handled like every other failure — clear the context, log,
     * continue — leaving {@code authorizeHttpRequests} to decide. Protected paths still 401,
     * which {@code UserControllerAuthorizationTest#expiredTokensAreUnauthorized} guards from the
     * other side; the pair of them is what pins the behaviour, since either alone could be
     * satisfied by a filter that was uniformly too strict or uniformly too lax.
     */
    @ParameterizedTest(name = "POST {0} with an expired token")
    @CsvSource({"/auth/login", "/auth/refresh", "/auth/revoke"})
    void expiredTokensDoNotBlockAnyAuthEndpoint(String path) throws Exception {
        stubEverything();

        String body = "/auth/login".equals(path) ? LOGIN_BODY : REFRESH_BODY;

        mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.expiredAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("POST %s with a stale expired token returned %d. These endpoints are "
                                + "permitAll and run before the caller holds a usable token; a "
                                + "expired credential must not lock a user out of re-authenticating.",
                                path, result.getResponse().getStatus())
                        .isNotIn(401, 403));
    }

    /**
     * Verifies that garbage in the {@code Authorization} header does not block login either.
     * {@code JwtFilter} catches an unparseable token, clears the security context and lets the
     * chain continue, so the permit rule still applies. Worth asserting separately from the
     * expired case because the filter handles the two through different branches — expiry
     * short-circuits with {@code sendError}, everything else falls through — and only one of
     * them can be inferred from the other.
     */
    @Test
    @DisplayName("login still works when the Authorization header is garbage")
    void malformedTokensDoNotBlockLogin() throws Exception {
        stubEverything();

        mockMvc.perform(post("/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.malformedToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk());
    }

    /**
     * Verifies login forwards the caller's credentials, PKCE verifier included, to the service.
     * The verifier is the part worth pinning: it is not used for routing or logging, so a binding
     * mistake that dropped it would leave the status green and silently disable the PKCE check.
     */
    @Test
    void loginForwardsTheCredentials() throws Exception {
        when(authorizationService.login(any())).thenReturn(aTokenPair());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk());

        LoginRequestDTO expected = new LoginRequestDTO();
        expected.setUsername("ada");
        expected.setPassword("correct-horse");
        expected.setCodeVerifier("pkce-verifier");
        verify(authorizationService).login(expected);
    }

    /** Verifies refresh forwards the whole request DTO to the token handler. */
    @Test
    void refreshForwardsTheRefreshToken() throws Exception {
        when(tokenHandlerService.refreshToken(any())).thenReturn(aTokenPair());

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(REFRESH_BODY))
                .andExpect(status().isOk());

        RefreshTokenRequestDTO expected = new RefreshTokenRequestDTO();
        expected.setRefreshToken("a-refresh-token");
        verify(tokenHandlerService).refreshToken(expected);
    }

    /**
     * Verifies revoke unwraps the DTO and passes the bare token string. Unlike refresh, this
     * endpoint calls {@code revokeToken(String)} rather than passing the DTO through, so the
     * unwrapping is a real step that could go wrong.
     */
    @Test
    void revokeForwardsTheBareToken() throws Exception {
        mockMvc.perform(post("/auth/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content(REFRESH_BODY))
                .andExpect(status().isOk());

        verify(tokenHandlerService).revokeToken("a-refresh-token");
    }

    /**
     * Verifies that this controller has exactly three endpoints and that ALL of them are
     * deliberately unprotected — the assertion runs the opposite way from the other four suites,
     * where an unannotated endpoint is a hole.
     *
     * <p>The value here is in the other direction: if someone later adds a genuinely
     * administrative endpoint to this controller (revoking another user's sessions, say) it will
     * land under the {@code /auth/**} permitAll rule and be anonymous by default. This test turns
     * that into a build failure, forcing a deliberate decision rather than an inherited one.
     */
    @Test
    @DisplayName("all three /auth endpoints are anonymous by design, and there are only three")
    void everyAuthEndpointIsDeliberatelyPublic() {
        assertThat(ControllerEndpoints.preAuthorizeAnnotatedMethods(AuthorizationController.class))
                .as("An @PreAuthorize appeared on /auth. These endpoints run before the caller "
                        + "holds a token, so method security here would deadlock the login flow "
                        + "unless the new endpoint is genuinely post-authentication.")
                .isEmpty();

        assertThat(ControllerEndpoints.handlerMethodsMissingAuthorization(AuthorizationController.class))
                .as("A new /auth endpoint inherits the permitAll path rule and is anonymous by "
                        + "default. Confirm that is intended, then add it here.")
                .containsExactlyInAnyOrder("login", "refresh", "revoke");
    }

    /**
     * Blanket stubs for the reachability tests, which only care whether security refused the
     * request. {@code login} and {@code refresh} both return their result straight into the
     * response, and {@code login} reads the request DTO for logging, so unstubbed nulls would
     * produce a 500 and obscure the status actually under assertion.
     */
    private void stubEverything() {
        when(authorizationService.login(any())).thenReturn(aTokenPair());
        when(tokenHandlerService.refreshToken(any())).thenReturn(aTokenPair());
        when(tokenHandlerService.generateRefreshToken(anyString(), anyString())).thenReturn("refresh");
    }
}
