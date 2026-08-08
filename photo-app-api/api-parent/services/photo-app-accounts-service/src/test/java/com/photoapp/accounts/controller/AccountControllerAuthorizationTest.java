package com.photoapp.accounts.controller;

import com.photoapp.accounts.service.AccountService;
import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.AccountTypeDTO;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import com.photoapp.test.support.security.TestJwt;
import com.photoapp.test.support.web.ControllerEndpoints;
import com.photoapp.test.support.web.PhotoAppSecuritySliceConfig;
import com.photoapp.test.support.web.ProtectedEndpoint;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 authorization matrix for {@link AccountController}.
 *
 * <p>Three of its eight endpoints are ADMIN-only — creating an account, changing an account's
 * type, and the bulk delete-by-user — while the {@code /accounts/**} path rule admits USER as
 * well. Everything narrower than the path rule is enforced by {@code @PreAuthorize} alone, so
 * these three are exactly the endpoints a method-security regression would silently open.
 *
 * <p>Token validity (expired, forged, malformed) is asserted once in the users-service suite
 * rather than repeated here: it is decided by {@code JwtFilter}, a single class from a shared
 * library, and repeating it per service would test one implementation five times while implying
 * a per-service behaviour that does not exist.
 *
 * <p>No Feign client is loaded. The slice registers this controller and a mocked
 * {@link AccountService}, nothing more, so the authorization boundary is measured in isolation
 * from the inter-service calls that are Phase 4's subject.
 */
@WebMvcTest
@ContextConfiguration(classes = AccountControllerAuthorizationTest.SliceContext.class)
@Import({AccountController.class, PhotoAppSecuritySliceConfig.class})
class AccountControllerAuthorizationTest {

    /*
        Replaces PhotoAppAccountsServiceApplication as the context root - see the same class in
        UserControllerAuthorizationTest for the full reasoning. Short version: the application
        class carries @EnableFeignClients and @EnableJpaAuditing, neither of which a web slice
        can satisfy, and SpringBootContextLoader does not detect nested @Configuration classes,
        so it has to be named explicitly for @Nested classes to inherit it.
     */
    @Configuration
    static class SliceContext {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    private static final String CREATE_BODY = """
            {"userId":1,"accountName":"Personal","accountTypeDTO":"BASIC"}""";

    /** Every {@code @PreAuthorize}-protected endpoint on this controller. */
    static Stream<ProtectedEndpoint> protectedEndpoints() {
        return Stream.of(
                ProtectedEndpoint.adminOnly("createAccount", HttpMethod.POST, "/accounts")
                        .withBody(CREATE_BODY),
                ProtectedEndpoint.adminOrUser("changeAccountName", HttpMethod.PATCH,
                        "/accounts/1/name?accountName=Renamed"),
                ProtectedEndpoint.adminOnly("changeAccountType", HttpMethod.PATCH,
                        "/accounts/1/type?accountTypeDTO=PREMIUM"),
                ProtectedEndpoint.adminOrUser("findById", HttpMethod.GET, "/accounts/1"),
                ProtectedEndpoint.adminOrUser("findAll", HttpMethod.GET, "/accounts"),
                ProtectedEndpoint.adminOrUser("activateOrDeactivate", HttpMethod.PATCH,
                        "/accounts/1/activate?activate=true"),
                ProtectedEndpoint.adminOrUser("deleteAccountById", HttpMethod.DELETE, "/accounts/1"),
                ProtectedEndpoint.adminOnly("deleteAccountByUserId", HttpMethod.DELETE, "/accounts/byUser/1")
        );
    }

    static Stream<ProtectedEndpoint> adminOnlyEndpoints() {
        return protectedEndpoints().filter(e -> e.access() == ProtectedEndpoint.Access.ADMIN_ONLY);
    }

    static Stream<ProtectedEndpoint> eitherRoleEndpoints() {
        return protectedEndpoints().filter(e -> e.access() == ProtectedEndpoint.Access.ADMIN_OR_USER);
    }

    /**
     * Verifies that every protected endpoint rejects an anonymous request with 401 and that the
     * service is never reached. 401 rather than 403 matters: the caller supplied no credential
     * at all, so the chain's {@code authenticationEntryPoint} must answer rather than its
     * {@code accessDeniedHandler}. The no-interaction check rules out the request having run and
     * its result merely being discarded.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void requestsWithoutATokenAreUnauthorized(ProtectedEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request())
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accountService);
    }

    /**
     * THE STEP 8 REGRESSION GUARD for this controller. Verifies that a valid ROLE_USER token on
     * each ADMIN-only endpoint yields 403 carrying the project's {@code ApiErrorDTO}, not merely
     * some 403. The body is the load-bearing assertion: this denial arrives as an
     * {@code AuthorizationDeniedException} thrown by {@code @PreAuthorize} inside the
     * DispatcherServlet and is rendered by {@code GlobalExceptionHandler}, which is precisely the
     * route that regressed into a 500 when no {@code AccessDeniedException} handler existed. A
     * plain-403 assertion would keep passing even if that ordering broke again.
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

        verifyNoInteractions(accountService);
    }

    /**
     * Verifies that a correctly signed token carrying a role outside the application's vocabulary
     * is denied everywhere. This denial happens one layer earlier than the case above — the
     * {@code /accounts/**} path rule requires USER or ADMIN, so the request never reaches method
     * security — which is why only the status is asserted. It is the case that would silently
     * open if that path rule were ever relaxed to {@code authenticated()}, and the five
     * either-role endpoints would then admit any signed token with nothing else noticing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void tokensWithAnUnknownRoleAreForbidden(ProtectedEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(
                                TestJwt.token("9", "outsider", "ROLE_GUEST"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accountService);
    }

    /**
     * Verifies that an ADMIN token reaches every protected endpoint — the positive control the
     * matrix needs, since a chain that denied everything would satisfy all the tests above.
     * Asserts only "security did not reject it" rather than a specific success status, because
     * each endpoint has its own (201, 200, 204) and response shape is not this suite's subject.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void adminTokensReachEveryProtectedEndpoint(ProtectedEndpoint endpoint) throws Exception {
        stubEverything();

        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.adminToken())))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("%s with an ADMIN token was rejected by security; "
                                + "it should have reached the controller", endpoint)
                        .isNotIn(401, 403));
    }

    /**
     * Verifies that a ROLE_USER token reaches the five endpoints whose {@code @PreAuthorize}
     * admits USER. The mirror image of the ADMIN-only 403 test: together they prove method
     * security discriminates between the two endpoint groups instead of applying one blanket
     * rule, which a suite exercising only ADMIN tokens could not tell apart.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("eitherRoleEndpoints")
    void userTokensReachEitherRoleEndpoints(ProtectedEndpoint endpoint) throws Exception {
        stubEverything();

        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.userToken())))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("%s with a USER token was rejected by security, but its "
                                + "@PreAuthorize admits ROLE_USER", endpoint)
                        .isNotIn(401, 403));
    }

    /**
     * Verifies a token holding BOTH roles is admitted everywhere rather than tripping over the
     * {@code hasRole('ADMIN') or hasRole('USER')} disjunction. This is the account shape that hid
     * the Step 8 defect: the developer accounts in daily use held both roles, so every endpoint
     * appeared to work by hand and the USER-only denial path was never exercised.
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
     * Nested because these assert argument forwarding rather than the matrix: an authorized
     * request must arrive at the service carrying the path variables, query parameters and body
     * the caller actually sent. Without them, "reached the controller" could mean the request was
     * mapped but its arguments silently dropped or coerced.
     */
    @Nested
    @DisplayName("authorized requests reach the service with the caller's arguments")
    class PassThrough {

        /** Verifies POST /accounts deserializes the whole body, enum field included. */
        @Test
        void createForwardsTheBody() throws Exception {
            when(accountService.createAccount(any())).thenReturn(AccountDTO.builder().id(1L).build());

            mockMvc.perform(asAdmin(HttpMethod.POST, "/accounts")
                            .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                    .andExpect(status().isCreated());

            verify(accountService).createAccount(CreateAccountInputDTO.builder()
                    .userId(1L).accountName("Personal").accountTypeDTO(AccountTypeDTO.BASIC).build());
        }

        /** Verifies PATCH /accounts/{id}/name forwards the id and the new name query parameter. */
        @Test
        void changeNameForwardsIdAndName() throws Exception {
            when(accountService.changeAccountName(anyLong(), anyString())).thenReturn(new AccountDTO());

            mockMvc.perform(asAdmin(HttpMethod.PATCH, "/accounts/4/name?accountName=Renamed"))
                    .andExpect(status().isOk());

            verify(accountService).changeAccountName(4L, "Renamed");
        }

        /**
         * Verifies PATCH /accounts/{id}/type converts the query parameter into the
         * {@link AccountTypeDTO} enum. Worth pinning separately: an unconvertible value here is
         * the type-mismatch shape Phase 2 turned from a 500 into a 400, and this test fixes the
         * happy path it was measured against.
         */
        @Test
        void changeTypeForwardsIdAndEnum() throws Exception {
            when(accountService.changeAccountType(anyLong(), any())).thenReturn(new AccountDTO());

            mockMvc.perform(asAdmin(HttpMethod.PATCH, "/accounts/6/type?accountTypeDTO=PREMIUM"))
                    .andExpect(status().isOk());

            verify(accountService).changeAccountType(6L, AccountTypeDTO.PREMIUM);
        }

        /** Verifies GET /accounts/{id} forwards the path id as a Long. */
        @Test
        void findByIdForwardsTheId() throws Exception {
            when(accountService.findById(anyLong())).thenReturn(new AccountDTO());

            mockMvc.perform(asAdmin(HttpMethod.GET, "/accounts/11"))
                    .andExpect(status().isOk());

            verify(accountService).findById(11L);
        }

        /** Verifies GET /accounts collects arbitrary query parameters into the filter map. */
        @Test
        void findAllForwardsTheFilterMap() throws Exception {
            when(accountService.findAll(any())).thenReturn(emptyPagedResponse());

            mockMvc.perform(asAdmin(HttpMethod.GET, "/accounts?page=1&size=5"))
                    .andExpect(status().isOk());

            verify(accountService).findAll(Map.of("page", "1", "size", "5"));
        }

        /** Verifies PATCH /accounts/{id}/activate forwards the id and the parsed boolean flag. */
        @Test
        void activateForwardsTheIdAndFlag() throws Exception {
            when(accountService.activateOrDeactivate(anyLong(), anyBoolean())).thenReturn(new AccountDTO());

            mockMvc.perform(asAdmin(HttpMethod.PATCH, "/accounts/2/activate?activate=false"))
                    .andExpect(status().isOk());

            verify(accountService).activateOrDeactivate(2L, false);
        }

        /** Verifies DELETE /accounts/{id} forwards the id and answers 204 with no body. */
        @Test
        void deleteByIdForwardsTheId() throws Exception {
            mockMvc.perform(asAdmin(HttpMethod.DELETE, "/accounts/3"))
                    .andExpect(status().isNoContent());

            verify(accountService).deleteById(3L);
        }

        /**
         * Verifies DELETE /accounts/byUser/{userId} routes to the by-user handler and not to the
         * by-id one. The two paths differ only by a literal segment, so a mapping mistake would
         * send a user id to {@code deleteById} and delete the wrong account entirely — hence
         * asserting the unwanted call did NOT happen as well as that the wanted one did.
         */
        @Test
        void deleteByUserIdForwardsTheUserIdToTheRightHandler() throws Exception {
            mockMvc.perform(asAdmin(HttpMethod.DELETE, "/accounts/byUser/77"))
                    .andExpect(status().isNoContent());

            verify(accountService).deleteByUserId(77L);
            verifyNoMoreInteractions(accountService);
        }
    }

    /**
     * Verifies this suite has not fallen behind the controller: reflects over
     * {@link AccountController}, collects every {@code @PreAuthorize} method, and fails unless
     * the table above names exactly that set. A protected endpoint added without a row here
     * therefore breaks the build rather than shipping untested.
     */
    @Test
    @DisplayName("every @PreAuthorize endpoint on the controller is in the matrix")
    void everyProtectedEndpointIsCovered() {
        Set<String> declared = ControllerEndpoints.preAuthorizeAnnotatedMethods(AccountController.class);
        Set<String> covered = protectedEndpoints()
                .map(ProtectedEndpoint::handlerMethod)
                .collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(declared).hasSize(8);
    }

    /**
     * Verifies this controller has NO unprotected endpoints. Unlike the users service, nothing
     * here is meant to be reachable anonymously — every account operation presupposes a
     * logged-in caller. An endpoint appearing in this set is a hole, not a design decision, and
     * would otherwise be invisible: the completeness test above only checks that protected
     * endpoints are covered, so an unprotected one would appear nowhere at all.
     */
    @Test
    @DisplayName("no endpoint on this controller is unprotected")
    void everyEndpointCarriesPreAuthorize() {
        assertThat(ControllerEndpoints.handlerMethodsMissingAuthorization(AccountController.class))
                .as("Every /accounts endpoint requires an authenticated caller; an endpoint "
                        + "without @PreAuthorize relies on the path rule alone, which is wider.")
                .isEmpty();
    }

    private static MockHttpServletRequestBuilder asAdmin(HttpMethod method, String path) {
        return MockMvcRequestBuilders.request(method, path)
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.adminToken()));
    }

    /**
     * Blanket stubs for the matrix tests, which walk every endpoint and care only about the
     * status security produced. {@code createAccount} dereferences its result to log the new id,
     * so Mockito's null default would turn an authorized request into a 500 and mask the very
     * outcome being asserted.
     */
    private void stubEverything() {
        when(accountService.createAccount(any())).thenReturn(AccountDTO.builder().id(1L).build());
        when(accountService.changeAccountName(anyLong(), anyString())).thenReturn(new AccountDTO());
        when(accountService.changeAccountType(anyLong(), any())).thenReturn(new AccountDTO());
        when(accountService.findById(anyLong())).thenReturn(new AccountDTO());
        when(accountService.findAll(any())).thenReturn(emptyPagedResponse());
        when(accountService.activateOrDeactivate(anyLong(), anyBoolean())).thenReturn(new AccountDTO());
    }
}
