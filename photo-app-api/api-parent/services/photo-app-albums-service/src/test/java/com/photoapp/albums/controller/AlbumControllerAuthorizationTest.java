package com.photoapp.albums.controller;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.albums.dto.UpdateAlbumInputDTO;
import com.photoapp.albums.service.AlbumService;
import com.photoapp.commons.dto.album.AlbumDTO;
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
import org.springframework.data.domain.PageImpl;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 authorization matrix for {@link AlbumController}.
 *
 * <p>Seven of its eight endpoints admit both roles; only the bulk
 * {@code DELETE /albums/byAccountIds} is ADMIN-only. That single narrow endpoint is the one
 * worth the most attention — it is the cascade delete the accounts service calls when an account
 * goes away, it takes a list of ids, and it is the only thing standing between a ROLE_USER token
 * and wiping out albums belonging to arbitrary accounts.
 *
 * <p>Token validity (expired, forged, malformed) is asserted once in the users-service suite:
 * {@code JwtFilter} is one class from a shared library, and repeating it per service would test
 * the same implementation five times.
 *
 * <p>No Feign client is loaded here — the slice registers this controller and a mocked
 * {@link AlbumService} and nothing else, keeping Phase 3 on the authorization boundary and off
 * the inter-service calls that belong to Phase 4.
 */
@WebMvcTest
@ContextConfiguration(classes = AlbumControllerAuthorizationTest.SliceContext.class)
@Import({AlbumController.class, PhotoAppSecuritySliceConfig.class})
class AlbumControllerAuthorizationTest {

    /*
        Replaces PhotoAppAlbumsServiceApplication as the context root - the application class
        carries @EnableFeignClients and @EnableJpaAuditing, which a web slice cannot satisfy.
        Named explicitly because SpringBootContextLoader does not detect nested @Configuration
        classes; without that, @Nested classes fall back to the package scan and find the
        application class anyway.
     */
    @Configuration
    static class SliceContext {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlbumService albumService;

    private static final String CREATE_BODY = """
            {"accountId":1,"title":"Holidays","description":"Summer 2026"}""";

    private static final String UPDATE_BODY = """
            {"title":"Renamed","description":"Still summer"}""";

    /** Every {@code @PreAuthorize}-protected endpoint on this controller. */
    static Stream<ProtectedEndpoint> protectedEndpoints() {
        return Stream.of(
                ProtectedEndpoint.adminOrUser("create", HttpMethod.POST, "/albums")
                        .withBody(CREATE_BODY),
                ProtectedEndpoint.adminOrUser("findById", HttpMethod.GET, "/albums/1"),
                ProtectedEndpoint.adminOrUser("findAll", HttpMethod.GET, "/albums"),
                ProtectedEndpoint.adminOrUser("countByAccountId", HttpMethod.GET,
                        "/albums/countByAccountId?accountId=1"),
                ProtectedEndpoint.adminOrUser("update", HttpMethod.PUT, "/albums/1")
                        .withBody(UPDATE_BODY),
                ProtectedEndpoint.adminOrUser("activateOrDeactivate", HttpMethod.PATCH,
                        "/albums/1/activate?activate=true"),
                ProtectedEndpoint.adminOrUser("delete", HttpMethod.DELETE, "/albums/1"),
                ProtectedEndpoint.adminOnly("deleteByAccountIds", HttpMethod.DELETE,
                        "/albums/byAccountIds?accountIds=1,2")
        );
    }

    static Stream<ProtectedEndpoint> adminOnlyEndpoints() {
        return protectedEndpoints().filter(e -> e.access() == ProtectedEndpoint.Access.ADMIN_ONLY);
    }

    static Stream<ProtectedEndpoint> eitherRoleEndpoints() {
        return protectedEndpoints().filter(e -> e.access() == ProtectedEndpoint.Access.ADMIN_OR_USER);
    }

    /**
     * Verifies every protected endpoint rejects an anonymous request with 401 and never reaches
     * the service. 401 rather than 403 is the meaningful distinction: no credential was offered,
     * so the {@code authenticationEntryPoint} must answer rather than the
     * {@code accessDeniedHandler}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void requestsWithoutATokenAreUnauthorized(ProtectedEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request())
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(albumService);
    }

    /**
     * THE STEP 8 REGRESSION GUARD for this controller, and the one assertion here with real
     * blast radius: verifies a ROLE_USER token cannot reach the bulk delete-by-account-ids
     * endpoint, and that the denial comes back as 403 carrying the project's
     * {@code ApiErrorDTO}. The body is what makes this a regression test rather than a status
     * check — the denial is an {@code AuthorizationDeniedException} raised by
     * {@code @PreAuthorize} inside the DispatcherServlet and rendered by
     * {@code GlobalExceptionHandler}, exactly the path that used to fall through to the
     * catch-all and return 500.
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

        verifyNoInteractions(albumService);
    }

    /**
     * Verifies a correctly signed token carrying an unknown role is denied on every endpoint.
     * This denial happens in the filter chain rather than at method security — the
     * {@code /albums/**} path rule requires USER or ADMIN — so only the status is asserted.
     * It matters most on this controller: seven of eight endpoints admit both roles, so the path
     * rule is the ONLY thing rejecting an unrecognised role on them, and relaxing it would open
     * all seven at once.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void tokensWithAnUnknownRoleAreForbidden(ProtectedEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(
                                TestJwt.token("9", "outsider", "ROLE_GUEST"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(albumService);
    }

    /**
     * Verifies an ADMIN token reaches every protected endpoint — the positive control, without
     * which a chain that denied everything would satisfy every test above. Asserts only that
     * security did not reject the request, since each endpoint returns its own success status and
     * response shape is not this suite's subject.
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
     * Verifies a ROLE_USER token reaches the seven endpoints whose {@code @PreAuthorize} admits
     * USER. Paired with the ADMIN-only 403 test, this proves method security is discriminating
     * between the two groups rather than applying one blanket rule — a distinction a suite
     * exercising only ADMIN tokens could not make.
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
     * Verifies a token holding both roles is admitted everywhere rather than confused by the
     * {@code hasRole('ADMIN') or hasRole('USER')} disjunction. This is the account shape that
     * concealed the Step 8 defect for months: every developer account held both roles, so manual
     * testing never exercised a USER-only denial.
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
     * request must arrive at the service with the path variables, query parameters and body the
     * caller sent. Without them, "reached the controller" could mean the request was mapped but
     * its arguments dropped or coerced.
     */
    @Nested
    @DisplayName("authorized requests reach the service with the caller's arguments")
    class PassThrough {

        /** Verifies POST /albums deserializes the full body including the optional description. */
        @Test
        void createForwardsTheBody() throws Exception {
            when(albumService.create(any())).thenReturn(AlbumDTO.builder().id(1L).build());

            mockMvc.perform(asAdmin(HttpMethod.POST, "/albums")
                            .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                    .andExpect(status().isCreated());

            verify(albumService).create(CreateAlbumInputDTO.builder()
                    .accountId(1L).title("Holidays").description("Summer 2026").build());
        }

        /** Verifies GET /albums/{id} forwards the path id as a Long. */
        @Test
        void findByIdForwardsTheId() throws Exception {
            when(albumService.findById(anyLong())).thenReturn(new AlbumDTO());

            mockMvc.perform(asAdmin(HttpMethod.GET, "/albums/12"))
                    .andExpect(status().isOk());

            verify(albumService).findById(12L);
        }

        /** Verifies GET /albums collects arbitrary query parameters into the filter map. */
        @Test
        void findAllForwardsTheFilterMap() throws Exception {
            when(albumService.findAll(any())).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(asAdmin(HttpMethod.GET, "/albums?accountIds=1&page=0"))
                    .andExpect(status().isOk());

            verify(albumService).findAll(Map.of("accountIds", "1", "page", "0"));
        }

        /** Verifies GET /albums/countByAccountId forwards the account id as a Long. */
        @Test
        void countByAccountIdForwardsTheAccountId() throws Exception {
            when(albumService.countByAccountId(anyLong())).thenReturn(3L);

            mockMvc.perform(asAdmin(HttpMethod.GET, "/albums/countByAccountId?accountId=8"))
                    .andExpect(status().isOk());

            verify(albumService).countByAccountId(8L);
        }

        /** Verifies PUT /albums/{id} forwards both the path id and the deserialized body. */
        @Test
        void updateForwardsIdAndBody() throws Exception {
            when(albumService.update(anyLong(), any())).thenReturn(new AlbumDTO());

            mockMvc.perform(asAdmin(HttpMethod.PUT, "/albums/5")
                            .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                    .andExpect(status().isOk());

            verify(albumService).update(5L, UpdateAlbumInputDTO.builder()
                    .title("Renamed").description("Still summer").build());
        }

        /** Verifies PATCH /albums/{id}/activate forwards the id and the parsed boolean flag. */
        @Test
        void activateForwardsTheIdAndFlag() throws Exception {
            when(albumService.activateOrDeactivate(anyLong(), anyBoolean())).thenReturn(new AlbumDTO());

            mockMvc.perform(asAdmin(HttpMethod.PATCH, "/albums/6/activate?activate=false"))
                    .andExpect(status().isOk());

            verify(albumService).activateOrDeactivate(6L, false);
        }

        /** Verifies DELETE /albums/{id} forwards the id and answers 204 with no body. */
        @Test
        void deleteForwardsTheId() throws Exception {
            mockMvc.perform(asAdmin(HttpMethod.DELETE, "/albums/7"))
                    .andExpect(status().isNoContent());

            verify(albumService).deleteById(7L);
        }

        /**
         * Verifies DELETE /albums/byAccountIds splits the comma-separated query parameter into a
         * list of Longs. Asserted because the list arrives as one string and a binding change
         * that produced {@code ["1,2"]} instead of {@code [1, 2]} would not fail any status
         * assertion — it would just silently delete nothing on a cascade.
         */
        @Test
        void deleteByAccountIdsForwardsAllIds() throws Exception {
            mockMvc.perform(asAdmin(HttpMethod.DELETE, "/albums/byAccountIds?accountIds=3,4,5"))
                    .andExpect(status().isNoContent());

            verify(albumService).deleteByAccountIds(List.of(3L, 4L, 5L));
        }
    }

    /**
     * Verifies this suite has not fallen behind the controller: reflects over
     * {@link AlbumController}, collects every {@code @PreAuthorize} method, and fails unless the
     * table above names exactly that set. A protected endpoint added without a row here breaks
     * the build rather than shipping untested.
     */
    @Test
    @DisplayName("every @PreAuthorize endpoint on the controller is in the matrix")
    void everyProtectedEndpointIsCovered() {
        Set<String> declared = ControllerEndpoints.preAuthorizeAnnotatedMethods(AlbumController.class);
        Set<String> covered = protectedEndpoints()
                .map(ProtectedEndpoint::handlerMethod)
                .collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(declared).hasSize(8);
    }

    /**
     * Verifies this controller has no unprotected endpoints. Nothing under {@code /albums} is
     * meant to be anonymous; an endpoint appearing here would rely on the path rule alone, which
     * is wider than every {@code @PreAuthorize} on the class, and the completeness test above
     * would not notice it because it only checks endpoints that ARE protected.
     */
    @Test
    @DisplayName("no endpoint on this controller is unprotected")
    void everyEndpointCarriesPreAuthorize() {
        assertThat(ControllerEndpoints.handlerMethodsMissingAuthorization(AlbumController.class))
                .isEmpty();
    }

    private static MockHttpServletRequestBuilder asAdmin(HttpMethod method, String path) {
        return MockMvcRequestBuilders.request(method, path)
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.adminToken()));
    }

    /**
     * Blanket stubs for the matrix tests, which walk every endpoint and care only about the
     * status security produced. Both {@code create} and {@code findAll} dereference their result
     * to log ({@code getId()}, {@code getTotalElements()}), so Mockito's null default would turn
     * an authorized request into a 500 and mask the authorization outcome being asserted.
     */
    private void stubEverything() {
        when(albumService.create(any())).thenReturn(AlbumDTO.builder().id(1L).build());
        when(albumService.findById(anyLong())).thenReturn(new AlbumDTO());
        when(albumService.findAll(any())).thenReturn(new PageImpl<>(List.of()));
        when(albumService.countByAccountId(anyLong())).thenReturn(0L);
        when(albumService.update(anyLong(), any())).thenReturn(new AlbumDTO());
        when(albumService.activateOrDeactivate(anyLong(), anyBoolean())).thenReturn(new AlbumDTO());
    }
}
