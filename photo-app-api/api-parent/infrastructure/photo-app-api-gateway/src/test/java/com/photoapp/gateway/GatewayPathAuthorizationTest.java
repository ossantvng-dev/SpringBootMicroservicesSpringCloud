package com.photoapp.gateway;

import com.photoapp.test.support.security.TestJwt;
import com.photoapp.test.support.web.PhotoAppSecuritySliceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 for the API gateway — the PATH-rule half of the authorization matrix.
 *
 * <p>The gateway has no controllers and therefore no {@code @PreAuthorize} anywhere; it
 * contributes none of the 32 protected endpoints. What it does contribute is the first line of
 * defence: it runs the same {@code SecurityConfiguration} chain in front of every proxied route,
 * so a request is authorized at the edge BEFORE it is forwarded downstream. That makes the path
 * rules the gateway's entire authorization surface, and it is the only component where all five
 * prefixes are in scope at once — each business service only ever sees its own.
 *
 * <p>Scope, stated plainly: this asserts the security chain as it is assembled on the gateway's
 * classpath, using probe endpoints mounted at the real prefixes. It does not exercise Spring
 * Cloud Gateway's routing, which is defined in the config server and is not available to a
 * slice test. Whether a permitted request is then routed to the right service is a different
 * question and not one Phase 3 asks.
 *
 * <p>The probes are deliberately bare — no {@code @PreAuthorize} — because method security is
 * what the four business-service suites cover. Anything that rejects a request here was rejected
 * by a path rule, which is exactly the layer under test.
 */
@WebMvcTest
@ContextConfiguration(classes = GatewayPathAuthorizationTest.SliceContext.class)
@Import({GatewayPathAuthorizationTest.Probes.class, PhotoAppSecuritySliceConfig.class})
class GatewayPathAuthorizationTest {

    /*
        Replaces PhotoAppApiGatewayApplication as the context root. That class component-scans
        com.photoapp.security, which would pull in JwtTokenProvider and its
        ${photoapp.jwt.validity} placeholder - resolved from the config server at runtime and
        unresolvable in a slice. PhotoAppSecuritySliceConfig supplies the parser the chain
        actually needs, built with the test secret.
     */
    @Configuration
    static class SliceContext {
    }

    /**
     * Stand-ins for the downstream services, mounted at the prefixes the gateway fronts. Reaching
     * one means the edge chain permitted the request; the real gateway would have proxied it at
     * this point.
     */
    @RestController
    static class Probes {
        @GetMapping("/users/1")
        String user() {
            return "reached";
        }

        @PostMapping("/users")
        String register() {
            return "reached";
        }

        @GetMapping("/users/username/ada")
        String usernameLookup() {
            return "reached";
        }

        @GetMapping("/accounts/1")
        String account() {
            return "reached";
        }

        @GetMapping("/albums/1")
        String album() {
            return "reached";
        }

        @GetMapping("/photos/1")
        String photo() {
            return "reached";
        }

        @PostMapping("/auth/login")
        String login() {
            return "reached";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "reached";
        }

        @GetMapping("/v3/api-docs")
        String apiDocs() {
            return "reached";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    /**
     * Verifies the gateway refuses anonymous traffic to every business prefix with a 401, so an
     * unauthenticated request is stopped at the edge and never proxied. This is the property that
     * makes the gateway a security boundary rather than a router: without it, the downstream
     * services would be the only thing standing between the internet and the data, and any
     * service reachable directly on the container network would be wide open.
     */
    @ParameterizedTest(name = "GET {0} without a token")
    @ValueSource(strings = {"/users/1", "/accounts/1", "/albums/1", "/photos/1"})
    void businessPrefixesRejectAnonymousRequests(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Verifies a signature-valid token carrying a role outside the application's vocabulary is
     * refused with 403 on every business prefix. Distinct from the anonymous case above: here the
     * caller IS authenticated, so this proves the prefixes are guarded by
     * {@code hasAnyRole("USER","ADMIN")} and not merely by {@code authenticated()} — a
     * substitution that would leave the anonymous test passing while admitting any signed token
     * the system ever issued.
     */
    @ParameterizedTest(name = "GET {0} with ROLE_GUEST")
    @ValueSource(strings = {"/users/1", "/accounts/1", "/albums/1", "/photos/1"})
    void businessPrefixesRejectUnknownRoles(String path) throws Exception {
        mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(
                                TestJwt.token("9", "outsider", "ROLE_GUEST"))))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifies both application roles are admitted through every business prefix. The positive
     * control for the two tests above — without it a chain that rejected everything would satisfy
     * them both — and it also pins that the path rules do NOT distinguish ADMIN from USER. That
     * distinction is deliberately left to method security on each service, so the gateway
     * admitting a USER token to {@code /users/**} is correct even though most of the endpoints
     * behind it are ADMIN-only.
     */
    @ParameterizedTest(name = "GET {0} as {1}")
    @CsvSource({
            "/users/1, ROLE_ADMIN", "/users/1, ROLE_USER",
            "/accounts/1, ROLE_ADMIN", "/accounts/1, ROLE_USER",
            "/albums/1, ROLE_ADMIN", "/albums/1, ROLE_USER",
            "/photos/1, ROLE_ADMIN", "/photos/1, ROLE_USER"
    })
    void businessPrefixesAdmitBothApplicationRoles(String path, String role) throws Exception {
        mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(
                                TestJwt.token("1", "someone", role))))
                .andExpect(status().isOk());
    }

    /**
     * Verifies the anonymous carve-outs survive at the edge. Each one exists for a concrete
     * reason and breaks a real flow if lost: registration has no caller to authenticate, the
     * username lookup is what login and refresh use to verify a user before any token exists,
     * {@code /auth/**} is where tokens come from, and {@code /actuator/health} is what the
     * container healthcheck polls without credentials.
     *
     * <p>These rules are order-sensitive — they must sit above the {@code /users/**} role rule,
     * since request matchers are evaluated in declaration order. Moving them below would make
     * {@code POST /users} require a token and quietly close registration.
     */
    @ParameterizedTest(name = "{1} {0} is anonymous")
    @CsvSource({
            "/users, POST",
            "/users/username/ada, GET",
            "/auth/login, POST",
            "/actuator/health, GET",
            "/v3/api-docs, GET"
    })
    void publicPathsStayAnonymous(String path, String method) throws Exception {
        var request = "POST".equals(method)
                ? post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                : get(path);

        mockMvc.perform(request)
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("%s %s was rejected by the gateway with %d, but it is permitAll and "
                                + "is reached before any caller holds a token",
                                method, path, result.getResponse().getStatus())
                        .isNotIn(401, 403));
    }

    /**
     * Verifies the gateway still declares no request-handling controllers of its own.
     *
     * <p>This is a scope guard, not a style rule. Every authorization decision in this suite is
     * a path rule, which is only the complete picture while the gateway remains a pure proxy. A
     * controller added here would carry endpoints that this suite does not describe and that no
     * business-service suite covers either, leaving a gap that nothing else would report.
     */
    @Test
    @DisplayName("the gateway declares no controllers, so path rules are its whole surface")
    void gatewayHasNoControllersOfItsOwn() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        /*
            Nested classes are excluded because this test's own Probes controller sits in the
            package being scanned. Every production controller in this codebase is a top-level
            class, so the filter costs no real coverage - and a nested production controller
            would still be caught by the business-service suites, which reflect over the
            controller they name rather than scanning.
         */
        var found = scanner.findCandidateComponents("com.photoapp.gateway").stream()
                .map(bean -> bean.getBeanClassName() == null ? "" : bean.getBeanClassName())
                .filter(name -> !name.contains("$"))
                .toList();

        assertThat(found)
                .as("The gateway declares a controller. Its endpoints are covered by no Phase 3 "
                        + "suite: this class asserts path rules only, and the business-service "
                        + "suites each reflect over their own controller.")
                .isEmpty();
    }
}
