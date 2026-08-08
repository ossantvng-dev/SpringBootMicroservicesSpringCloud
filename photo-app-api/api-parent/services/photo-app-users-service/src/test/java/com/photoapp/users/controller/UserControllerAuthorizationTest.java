package com.photoapp.users.controller;

import com.photoapp.commons.dto.role.RoleAction;
import com.photoapp.commons.dto.role.RoleNameDTO;
import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.entity.User;
import com.photoapp.test.support.security.TestJwt;
import com.photoapp.test.support.web.ControllerEndpoints;
import com.photoapp.test.support.web.PhotoAppSecuritySliceConfig;
import com.photoapp.test.support.web.ProtectedEndpoint;
import com.photoapp.users.dto.CreateUserInputDTO;
import com.photoapp.users.dto.UpdateUserInputDTO;
import com.photoapp.users.dto.UpdateUserRolesInputDTO;
import com.photoapp.users.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static com.photoapp.test.support.fixtures.TestPages.emptyPagedResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 authorization matrix for {@link UserController}.
 *
 * <p>This is the richest of the five controllers for authorization purposes: six of its eight
 * protected endpoints are {@code ADMIN}-only while the surrounding path rule
 * ({@code /users/**} → {@code hasAnyRole("USER","ADMIN")}) is deliberately wider. That gap is
 * the whole point — a USER token gets through the filter chain and is stopped by method
 * security, which is the exact code path the Step 8 defect broke.
 *
 * <p>Named {@code ...AuthorizationTest} rather than the plan's generic
 * {@code ...WebMvcTest}: this suite asserts only the authorization boundary, and a later phase
 * will want {@code UserControllerWebMvcTest} for request binding, validation and response
 * shape. Splitting them keeps each file's failures unambiguous.
 *
 * <p>Feign is deliberately absent. Nothing here loads a Feign client — the slice registers the
 * controller and a mocked {@link UserService} and nothing else — so Phase 3 cannot accidentally
 * become a test of inter-service calls. That is Phase 4.
 */
@WebMvcTest
@ContextConfiguration(classes = UserControllerAuthorizationTest.SliceContext.class)
@Import({UserController.class, PhotoAppSecuritySliceConfig.class})
class UserControllerAuthorizationTest {

    /*
        Replaces PhotoAppUsersServiceApplication as the context root.

        Required, not stylistic. The real application class carries @EnableFeignClients,
        @EnableJpaAuditing, @EnableDiscoveryClient and @EntityScan; a @WebMvcTest slice
        auto-configures only the web layer, so those registrars would ask for a Feign
        infrastructure and a JPA metamodel that the slice never builds - the Feign one being
        exactly the Phase 4 boundary this suite must not cross.

        Named explicitly in @ContextConfiguration rather than left to nested-class detection:
        SpringBootContextLoader does not detect nested @Configuration classes the way the plain
        loader does, so @Nested inner test classes fell back to searching the package and found
        the application class anyway. Declaring it is inherited by @Nested classes; relying on
        detection is not.
     */
    @Configuration
    static class SliceContext {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    private static final String UPDATE_BODY = """
            {"firstName":"Ada","lastName":"Lovelace","email":"ada@example.com"}""";

    private static final String ROLES_BODY = """
            {"action":"ASSIGN","roles":["ROLE_ADMIN"]}""";

    /**
     * Every {@code @PreAuthorize}-protected endpoint on this controller, with the roles its own
     * method security admits. Public endpoints are excluded by design and asserted separately in
     * {@link PublicEndpoints}.
     */
    static Stream<ProtectedEndpoint> protectedEndpoints() {
        return Stream.of(
                ProtectedEndpoint.adminOrUser("update", HttpMethod.PUT, "/users/1")
                        .withBody(UPDATE_BODY),
                ProtectedEndpoint.adminOrUser("findById", HttpMethod.GET, "/users/1"),
                ProtectedEndpoint.adminOnly("findByEmail", HttpMethod.GET, "/users/email/ada@example.com"),
                ProtectedEndpoint.adminOnly("isActive", HttpMethod.GET, "/users/1/active"),
                ProtectedEndpoint.adminOnly("findAll", HttpMethod.GET, "/users"),
                ProtectedEndpoint.adminOnly("activateOrDeactivate", HttpMethod.PATCH, "/users/1/activate?activate=true"),
                ProtectedEndpoint.adminOnly("assignOrRemoveRole", HttpMethod.PATCH, "/users/1/roles")
                        .withBody(ROLES_BODY),
                ProtectedEndpoint.adminOnly("deleteById", HttpMethod.DELETE, "/users/1")
        );
    }

    /** The {@code ADMIN}-only subset, where a USER token must be rejected by method security. */
    static Stream<ProtectedEndpoint> adminOnlyEndpoints() {
        return protectedEndpoints()
                .filter(e -> e.access() == ProtectedEndpoint.Access.ADMIN_ONLY);
    }

    // =================================================================================
    // State 1 - no credential
    // =================================================================================

    /**
     * Verifies that every protected endpoint rejects an anonymous request with 401 and never
     * reaches the service layer. 401 rather than 403 is the meaningful part: the caller has not
     * identified itself at all, so the chain's {@code authenticationEntryPoint} must answer,
     * not its {@code accessDeniedHandler}. Also asserts the service was never touched, because a
     * status assertion alone would still pass if the controller ran and its result were
     * discarded.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void requestsWithoutATokenAreUnauthorized(ProtectedEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request())
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    /**
     * Verifies that a token which is well-formed and correctly signed but has already expired is
     * treated as no credential at all — 401, not 403 and not a pass-through. Minted through the
     * {@code Clock} seam rather than by sleeping, so the test is instant and deterministic.
     *
     * <p>Asserted once here rather than in all five suites: token validity is decided by
     * {@code JwtFilter}, one class from one shared library, so repeating it per service would
     * test the same code five times and imply a per-service behaviour that does not exist.
     */
    @Test
    @DisplayName("an expired token is 401, not 403")
    void expiredTokensAreUnauthorized() throws Exception {
        mockMvc.perform(get("/users/1")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(
                                TestJwt.expiredToken(Duration.ofMinutes(5), "1", "admin", TestJwt.ROLE_ADMIN))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    /**
     * Verifies that a token signed with a key other than the configured secret is rejected with
     * 401. This is the forgery case: the payload claims {@code ROLE_ADMIN}, so if signature
     * verification were skipped or misconfigured the request would sail through to an
     * ADMIN-only endpoint. A 200 here would be a critical vulnerability, and the assertion is
     * deliberately made against a real ADMIN-only endpoint rather than a synthetic one.
     */
    @Test
    @DisplayName("a token signed with the wrong key is 401, even claiming ROLE_ADMIN")
    void forgedTokensAreUnauthorized() throws Exception {
        mockMvc.perform(get("/users")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.tokenWithWrongSignature())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    /**
     * Verifies that a string which is not a JWT at all is rejected with 401 rather than crashing
     * the filter. {@code JwtFilter} catches the parse failure, clears the context and lets the
     * chain deny the request — so the observable outcome must be an ordinary 401, not a 500 from
     * an exception escaping the filter.
     */
    @Test
    @DisplayName("a malformed Authorization header is 401, not 500")
    void malformedTokensAreUnauthorized() throws Exception {
        mockMvc.perform(get("/users")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.malformedToken())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    // =================================================================================
    // State 2 - authenticated, insufficient permission
    // =================================================================================

    /**
     * THE STEP 8 REGRESSION GUARD, asserted per endpoint.
     *
     * <p>Verifies that a valid ROLE_USER token on each of the six ADMIN-only endpoints yields
     * 403 — and, critically, that the 403 body is the project's {@code ApiErrorDTO}. The status
     * alone is not sufficient evidence: this denial is raised by {@code @PreAuthorize} as an
     * {@code AuthorizationDeniedException} from inside the DispatcherServlet, so it is routed by
     * {@code GlobalExceptionHandler}, and before the Step 8 fix it fell through to the
     * {@code Exception} catch-all and came back as a 500. A generic 403 body would mean the
     * request was stopped a layer earlier by the filter chain, which is a different code path
     * that would keep passing even if the handler ordering regressed again.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("adminOnlyEndpoints")
    void userTokensAreForbiddenOnAdminOnlyEndpoints(ProtectedEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.userToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.httpStatus").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.timeStamp").exists());

        verifyNoInteractions(userService);
    }

    /**
     * Verifies that a signature-valid token carrying a role outside the application's vocabulary
     * is denied on every protected endpoint. This is denial one layer earlier than the case
     * above — the {@code /users/**} path rule requires USER or ADMIN, so the request never
     * reaches method security — which is why only the status is asserted and not the body.
     *
     * <p>Worth testing because it is the case a permissive path rule would silently open: if
     * {@code /users/**} were ever loosened to {@code authenticated()}, the ADMIN-only endpoints
     * would still be safe but the two either-role endpoints would quietly admit any signed
     * token at all, and no other test in this suite would notice.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void tokensWithAnUnknownRoleAreForbidden(ProtectedEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(
                                TestJwt.token("9", "outsider", "ROLE_GUEST"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    // =================================================================================
    // State 3 - authorized, reaches the controller with the right arguments
    // =================================================================================

    /**
     * Verifies that an ADMIN token reaches every protected endpoint — the positive control
     * without which the whole matrix could pass by denying everything. Deliberately asserts only
     * "not rejected by security" rather than a specific success status, because each endpoint
     * returns its own (200, 201, 204) and this suite is not about response shape. The individual
     * pass-through tests below then pin the exact arguments the controller forwarded.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void adminTokensReachEveryProtectedEndpoint(ProtectedEndpoint endpoint) throws Exception {
        stubEverything();

        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.adminToken())))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status)
                            .as("%s with an ADMIN token was rejected by security with %d; "
                                    + "it should have reached the controller", endpoint, status)
                            .isNotIn(401, 403);
                });
    }

    /**
     * Verifies that a ROLE_USER token reaches the two endpoints whose {@code @PreAuthorize}
     * admits USER. The mirror image of the ADMIN-only 403 test: together they prove method
     * security is discriminating between the two endpoint groups rather than applying one blanket
     * rule, which a suite testing only ADMIN tokens could not distinguish.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("eitherRoleEndpoints")
    void userTokensReachEitherRoleEndpoints(ProtectedEndpoint endpoint) throws Exception {
        stubEverything();

        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.userToken())))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status)
                            .as("%s with a USER token was rejected by security with %d; "
                                    + "its @PreAuthorize admits ROLE_USER", endpoint, status)
                            .isNotIn(401, 403);
                });
    }

    static Stream<ProtectedEndpoint> eitherRoleEndpoints() {
        return protectedEndpoints()
                .filter(e -> e.access() == ProtectedEndpoint.Access.ADMIN_OR_USER);
    }

    /**
     * Verifies that a token carrying BOTH roles is admitted rather than confused by the
     * {@code hasRole('ADMIN') or hasRole('USER')} disjunction. This is the account shape that hid
     * the Step 8 defect for months: the developer accounts in use all held both roles, so every
     * endpoint appeared to work and the USER-only denial path was never exercised by hand.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void tokensWithBothRolesReachEveryEndpoint(ProtectedEndpoint endpoint) throws Exception {
        stubEverything();

        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.adminAndUserToken())))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("%s rejected a token holding ROLE_ADMIN and ROLE_USER", endpoint)
                        .isNotIn(401, 403));
    }

    /**
     * Nested because these assert argument forwarding rather than the matrix: for each protected
     * endpoint, an authorized request must arrive at the service with the path variables, query
     * parameters and body the caller actually sent. Without this, "reached the controller" could
     * mean the request was mapped but its arguments silently dropped.
     */
    @Nested
    @DisplayName("authorized requests reach the service with the caller's arguments")
    class PassThrough {

        /** Verifies PUT /users/{id} forwards both the path id and the deserialized body. */
        @Test
        void updateForwardsIdAndBody() throws Exception {
            when(userService.update(anyLong(), any())).thenReturn(new UserDTO());

            mockMvc.perform(asAdmin(HttpMethod.PUT, "/users/7").contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isOk());

            verify(userService).update(7L, UpdateUserInputDTO.builder()
                    .firstName("Ada").lastName("Lovelace").email("ada@example.com").build());
        }

        /** Verifies GET /users/{id} forwards the path id as a Long. */
        @Test
        void findByIdForwardsTheId() throws Exception {
            when(userService.findById(anyLong())).thenReturn(new UserDTO());

            mockMvc.perform(asAdmin(HttpMethod.GET, "/users/42"))
                    .andExpect(status().isOk());

            verify(userService).findById(42L);
        }

        /**
         * Verifies GET /users/email/{email} forwards the address unmangled. The dot and @ in an
         * address are the classic case where Spring's path matching truncates at the extension
         * separator, so the assertion is on the exact string, not merely that the call happened.
         */
        @Test
        void findByEmailForwardsTheWholeAddress() throws Exception {
            when(userService.findByEmail(anyString())).thenReturn(new UserDTO());

            mockMvc.perform(asAdmin(HttpMethod.GET, "/users/email/ada@example.com"))
                    .andExpect(status().isOk());

            verify(userService).findByEmail("ada@example.com");
        }

        /** Verifies GET /users/{id}/active forwards the id to the existence check. */
        @Test
        void isActiveForwardsTheId() throws Exception {
            when(userService.existsById(anyLong())).thenReturn(true);

            mockMvc.perform(asAdmin(HttpMethod.GET, "/users/3/active"))
                    .andExpect(status().isOk());

            verify(userService).existsById(3L);
        }

        /**
         * Verifies GET /users collects arbitrary query parameters into the filter map. The
         * controller takes {@code @RequestParam Map<String,String>}, so anything the caller sends
         * arrives — this pins that the map is populated rather than empty.
         */
        @Test
        void findAllForwardsTheFilterMap() throws Exception {
            when(userService.findAll(any())).thenReturn(emptyPagedResponse());

            mockMvc.perform(asAdmin(HttpMethod.GET, "/users?page=0&size=25"))
                    .andExpect(status().isOk());

            verify(userService).findAll(Map.of("page", "0", "size", "25"));
        }

        /** Verifies PATCH /users/{id}/activate forwards the id and the parsed boolean flag. */
        @Test
        void activateForwardsTheIdAndFlag() throws Exception {
            when(userService.activateOrDeactivate(anyLong(), anyBoolean())).thenReturn(new UserDTO());

            mockMvc.perform(asAdmin(HttpMethod.PATCH, "/users/5/activate?activate=false"))
                    .andExpect(status().isOk());

            verify(userService).activateOrDeactivate(5L, false);
        }

        /** Verifies PATCH /users/{id}/roles forwards the id and the deserialized role action. */
        @Test
        void assignRoleForwardsTheIdAndAction() throws Exception {
            when(userService.assignOrRemoveRole(anyLong(), any())).thenReturn(new UserDTO());

            mockMvc.perform(asAdmin(HttpMethod.PATCH, "/users/8/roles")
                            .contentType(MediaType.APPLICATION_JSON).content(ROLES_BODY))
                    .andExpect(status().isOk());

            verify(userService).assignOrRemoveRole(8L, UpdateUserRolesInputDTO.builder()
                    .action(RoleAction.ASSIGN)
                    .roles(Set.of(RoleNameDTO.ROLE_ADMIN))
                    .build());
        }

        /** Verifies DELETE /users/{id} forwards the id and answers 204 with no body. */
        @Test
        void deleteForwardsTheId() throws Exception {
            mockMvc.perform(asAdmin(HttpMethod.DELETE, "/users/9"))
                    .andExpect(status().isNoContent());

            verify(userService).deleteById(9L);
        }
    }

    // =================================================================================
    // Public endpoints
    // =================================================================================

    /**
     * Nested because these endpoints are the inverse assertion: they must NOT require a token.
     * Both are load-bearing for flows that run before a caller has one — registration, and the
     * username lookup that login and refresh use to re-verify a user.
     */
    @Nested
    @DisplayName("deliberately public endpoints stay reachable anonymously")
    class PublicEndpoints {

        /**
         * Verifies POST /users is reachable with no Authorization header. Registration cannot
         * require a token by definition; if the {@code permitAll} rule for it were ever dropped,
         * new users could never be created and this is the only test that would say so.
         */
        @Test
        void registrationIsAnonymous() throws Exception {
            when(userService.register(any())).thenReturn(UserDTO.builder().id(1L).build());

            mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("""
                            {"firstName":"Ada","lastName":"Lovelace","username":"ada",
                             "email":"ada@example.com","password":"correct-horse"}"""))
                    .andExpect(status().isCreated());

            verify(userService).register(any(CreateUserInputDTO.class));
        }

        /**
         * Verifies GET /users/username/{username} is reachable anonymously. This one is subtle:
         * it is public because the authorization service calls it during login and during
         * refresh, at which points the caller has no access token by definition. Making it
         * require one would deadlock the login flow.
         */
        @Test
        void usernameLookupIsAnonymous() throws Exception {
            when(userService.findByUsernameAndActiveUser(anyString(), anyBoolean()))
                    .thenReturn(new User());

            mockMvc.perform(get("/users/username/ada"))
                    .andExpect(status().isOk());

            verify(userService).findByUsernameAndActiveUser("ada", true);
        }
    }

    // =================================================================================
    // Completeness
    // =================================================================================

    /**
     * Verifies this suite has not fallen behind the controller: reflects over
     * {@link UserController}, collects every {@code @PreAuthorize} method, and fails unless the
     * table above names exactly that set. Adding a protected endpoint without adding a row here
     * therefore breaks the build rather than shipping with no authorization test at all.
     *
     * <p>The same guard caught a missing handler on its first run in Phase 2, which is why it is
     * repeated per controller here.
     */
    @Test
    @DisplayName("every @PreAuthorize endpoint on the controller is in the matrix")
    void everyProtectedEndpointIsCovered() {
        Set<String> declared = ControllerEndpoints.preAuthorizeAnnotatedMethods(UserController.class);
        Set<String> covered = protectedEndpoints()
                .map(ProtectedEndpoint::handlerMethod)
                .collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(declared).hasSize(8);
    }

    /**
     * Verifies that exactly two endpoints are unprotected, and that they are the two intended
     * ones. Without this, a new endpoint added without {@code @PreAuthorize} would be invisible:
     * the test above only checks that protected endpoints are covered, so an unprotected one
     * would not appear anywhere and the suite would stay green while the hole shipped.
     */
    @Test
    @DisplayName("only the two intended endpoints are unprotected")
    void noUnintendedPublicEndpoints() {
        assertThat(ControllerEndpoints.handlerMethodsMissingAuthorization(UserController.class))
                .as("An endpoint without @PreAuthorize is either deliberately public - in which "
                        + "case add it here and to PublicEndpoints - or a hole.")
                .containsExactlyInAnyOrder("register", "findByUsernameAndActiveUser");
    }

    // =================================================================================
    // Helpers
    // =================================================================================

    private static MockHttpServletRequestBuilder asAdmin(HttpMethod method, String path) {
        return MockMvcRequestBuilders.request(method, path)
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.adminToken()));
    }

    /**
     * Blanket stubs for the matrix tests, which walk every endpoint and care only about the
     * status security produced. Two of the controller methods dereference the service result
     * ({@code findAll} reads {@code getTotalElements()}), so returning Mockito's null default
     * would produce a 500 and mask the authorization outcome the test is actually asserting.
     */
    private void stubEverything() {
        when(userService.update(anyLong(), any())).thenReturn(new UserDTO());
        when(userService.findById(anyLong())).thenReturn(new UserDTO());
        when(userService.findByEmail(anyString())).thenReturn(new UserDTO());
        when(userService.existsById(anyLong())).thenReturn(true);
        when(userService.findAll(any())).thenReturn(emptyPagedResponse());
        when(userService.activateOrDeactivate(anyLong(), anyBoolean())).thenReturn(new UserDTO());
        when(userService.assignOrRemoveRole(anyLong(), any())).thenReturn(new UserDTO());
    }
}
