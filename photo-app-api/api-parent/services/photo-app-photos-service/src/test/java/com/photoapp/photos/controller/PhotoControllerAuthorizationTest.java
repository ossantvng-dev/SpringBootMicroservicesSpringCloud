package com.photoapp.photos.controller;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.photos.dto.CreatePhotoInputDTO;
import com.photoapp.photos.dto.UpdatePhotoInputDTO;
import com.photoapp.photos.service.PhotoService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 authorization matrix for {@link PhotoController}.
 *
 * <p>Structurally the twin of the albums controller: seven endpoints open to both roles and one
 * ADMIN-only bulk delete, {@code DELETE /photos/byAlbumIds}, which sits at the end of the
 * account → album → photo cascade. It is the deepest destructive endpoint in the system and the
 * only one on this controller where {@code @PreAuthorize} is narrower than the path rule.
 *
 * <p>Token validity (expired, forged, malformed) is asserted once in the users-service suite —
 * {@code JwtFilter} is a single shared class, and asserting it five times would test one
 * implementation repeatedly while implying a per-service behaviour that does not exist.
 *
 * <p>No Feign client is loaded: the slice holds this controller and a mocked
 * {@link PhotoService}, which keeps Phase 3 on the authorization boundary and leaves
 * inter-service calls to Phase 4.
 */
@WebMvcTest
@ContextConfiguration(classes = PhotoControllerAuthorizationTest.SliceContext.class)
@Import({PhotoController.class, PhotoAppSecuritySliceConfig.class})
class PhotoControllerAuthorizationTest {

    /*
        Replaces PhotoAppPhotosServiceApplication as the context root - it carries
        @EnableFeignClients and @EnableJpaAuditing, which a web slice cannot satisfy. Named
        explicitly because SpringBootContextLoader does not detect nested @Configuration classes,
        so @Nested classes would otherwise fall back to the package scan and find the application
        class regardless.
     */
    @Configuration
    static class SliceContext {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PhotoService photoService;

    private static final String CREATE_BODY = """
            {"albumId":1,"fileName":"beach.jpg","fileUrl":"https://cdn.example.com/beach.jpg"}""";

    private static final String UPDATE_BODY = """
            {"fileName":"sunset.jpg","fileUrl":"https://cdn.example.com/sunset.jpg"}""";

    /** Every {@code @PreAuthorize}-protected endpoint on this controller. */
    static Stream<ProtectedEndpoint> protectedEndpoints() {
        return Stream.of(
                ProtectedEndpoint.adminOrUser("create", HttpMethod.POST, "/photos")
                        .withBody(CREATE_BODY),
                ProtectedEndpoint.adminOrUser("findById", HttpMethod.GET, "/photos/1"),
                ProtectedEndpoint.adminOrUser("findAll", HttpMethod.GET, "/photos"),
                ProtectedEndpoint.adminOrUser("countByAlbumIds", HttpMethod.GET,
                        "/photos/countByAlbumIds?albumIds=1,2"),
                ProtectedEndpoint.adminOrUser("update", HttpMethod.PUT, "/photos/1")
                        .withBody(UPDATE_BODY),
                ProtectedEndpoint.adminOrUser("activateOrDeactivate", HttpMethod.PATCH,
                        "/photos/1/activate?activate=true"),
                ProtectedEndpoint.adminOrUser("delete", HttpMethod.DELETE, "/photos/1"),
                ProtectedEndpoint.adminOnly("deleteByAlbumIds", HttpMethod.DELETE,
                        "/photos/byAlbumIds?albumIds=1,2")
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
     * the service. 401 rather than 403 is the point: no credential was offered at all, so the
     * chain's {@code authenticationEntryPoint} must answer, not its {@code accessDeniedHandler}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void requestsWithoutATokenAreUnauthorized(ProtectedEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request())
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(photoService);
    }

    /**
     * THE STEP 8 REGRESSION GUARD for this controller. Verifies a ROLE_USER token cannot reach
     * the bulk delete-by-album-ids endpoint and that the denial is a 403 carrying the project's
     * {@code ApiErrorDTO}. The body assertion is what makes this a regression test: the denial is
     * an {@code AuthorizationDeniedException} thrown by {@code @PreAuthorize} inside the
     * DispatcherServlet and rendered by {@code GlobalExceptionHandler} — the exact path that fell
     * through to the {@code Exception} catch-all and returned 500 before the Step 8 fix.
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

        verifyNoInteractions(photoService);
    }

    /**
     * Verifies a correctly signed token carrying an unknown role is denied on every endpoint.
     * The denial happens in the filter chain rather than at method security — {@code /photos/**}
     * requires USER or ADMIN — so only the status is asserted. It carries the most weight on this
     * controller because seven of eight endpoints admit both roles, leaving the path rule as the
     * only thing rejecting an unrecognised role on them.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void tokensWithAnUnknownRoleAreForbidden(ProtectedEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request()
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(
                                TestJwt.token("9", "outsider", "ROLE_GUEST"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(photoService);
    }

    /**
     * Verifies an ADMIN token reaches every protected endpoint — the positive control, without
     * which a chain that simply denied everything would satisfy all the tests above. Asserts only
     * that security did not reject the request; each endpoint has its own success status and
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
     * USER. Paired with the ADMIN-only 403 test, this proves method security discriminates
     * between the two endpoint groups instead of applying one blanket rule — something a suite
     * exercising only ADMIN tokens could not distinguish.
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
     * {@code hasRole('ADMIN') or hasRole('USER')} disjunction — the account shape that hid the
     * Step 8 defect, since every developer account held both roles and manual testing therefore
     * never exercised a USER-only denial.
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
     * caller sent, not merely be routed to the right method.
     */
    @Nested
    @DisplayName("authorized requests reach the service with the caller's arguments")
    class PassThrough {

        /** Verifies POST /photos deserializes the whole body, including the URL field. */
        @Test
        void createForwardsTheBody() throws Exception {
            when(photoService.create(any())).thenReturn(photoWithId(1L));

            mockMvc.perform(asAdmin(HttpMethod.POST, "/photos")
                            .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                    .andExpect(status().isOk());

            CreatePhotoInputDTO expected = new CreatePhotoInputDTO();
            expected.setAlbumId(1L);
            expected.setFileName("beach.jpg");
            expected.setFileUrl("https://cdn.example.com/beach.jpg");
            verify(photoService).create(expected);
        }

        /** Verifies GET /photos/{id} forwards the path id as a Long. */
        @Test
        void findByIdForwardsTheId() throws Exception {
            when(photoService.findById(anyLong())).thenReturn(new PhotoDTO());

            mockMvc.perform(asAdmin(HttpMethod.GET, "/photos/14"))
                    .andExpect(status().isOk());

            verify(photoService).findById(14L);
        }

        /** Verifies GET /photos collects arbitrary query parameters into the filter map. */
        @Test
        void findAllForwardsTheFilterMap() throws Exception {
            when(photoService.findAll(any())).thenReturn(emptyPagedResponse());

            mockMvc.perform(asAdmin(HttpMethod.GET, "/photos?albumId=2&size=10"))
                    .andExpect(status().isOk());

            verify(photoService).findAll(Map.of("albumId", "2", "size", "10"));
        }

        /**
         * Verifies GET /photos/countByAlbumIds splits the comma-separated parameter into a list of
         * Longs rather than passing a single joined string. The counts drive the album service's
         * cascade decisions, so a binding change producing {@code ["1,2"]} would return a wrong
         * count without any status changing.
         */
        @Test
        void countByAlbumIdsForwardsAllIds() throws Exception {
            when(photoService.countByAlbumIdIn(any())).thenReturn(5L);

            mockMvc.perform(asAdmin(HttpMethod.GET, "/photos/countByAlbumIds?albumIds=1,2,3"))
                    .andExpect(status().isOk());

            verify(photoService).countByAlbumIdIn(List.of(1L, 2L, 3L));
        }

        /** Verifies PUT /photos/{id} forwards both the path id and the deserialized body. */
        @Test
        void updateForwardsIdAndBody() throws Exception {
            when(photoService.update(anyLong(), any())).thenReturn(new PhotoDTO());

            mockMvc.perform(asAdmin(HttpMethod.PUT, "/photos/5")
                            .contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                    .andExpect(status().isOk());

            UpdatePhotoInputDTO expected = new UpdatePhotoInputDTO();
            expected.setFileName("sunset.jpg");
            expected.setFileUrl("https://cdn.example.com/sunset.jpg");
            verify(photoService).update(5L, expected);
        }

        /** Verifies PATCH /photos/{id}/activate forwards the id and the parsed boolean flag. */
        @Test
        void activateForwardsTheIdAndFlag() throws Exception {
            when(photoService.activateOrDeactivate(anyLong(), anyBoolean())).thenReturn(new PhotoDTO());

            mockMvc.perform(asAdmin(HttpMethod.PATCH, "/photos/6/activate?activate=false"))
                    .andExpect(status().isOk());

            verify(photoService).activateOrDeactivate(6L, false);
        }

        /** Verifies DELETE /photos/{id} forwards the id and answers 204 with no body. */
        @Test
        void deleteForwardsTheId() throws Exception {
            mockMvc.perform(asAdmin(HttpMethod.DELETE, "/photos/7"))
                    .andExpect(status().isNoContent());

            verify(photoService).deleteById(7L);
        }

        /**
         * Verifies DELETE /photos/byAlbumIds splits the comma-separated parameter into a list of
         * Longs. This is the last step of the account → album → photo cascade, so a binding
         * change that silently produced the wrong list would leave orphaned rows behind with
         * every status assertion still green.
         */
        @Test
        void deleteByAlbumIdsForwardsAllIds() throws Exception {
            mockMvc.perform(asAdmin(HttpMethod.DELETE, "/photos/byAlbumIds?albumIds=3,4"))
                    .andExpect(status().isNoContent());

            verify(photoService).deleteByAlbumIds(List.of(3L, 4L));
        }
    }

    /**
     * Verifies this suite has not fallen behind the controller: reflects over
     * {@link PhotoController}, collects every {@code @PreAuthorize} method, and fails unless the
     * table above names exactly that set. A protected endpoint added without a row here breaks
     * the build rather than shipping untested.
     */
    @Test
    @DisplayName("every @PreAuthorize endpoint on the controller is in the matrix")
    void everyProtectedEndpointIsCovered() {
        Set<String> declared = ControllerEndpoints.preAuthorizeAnnotatedMethods(PhotoController.class);
        Set<String> covered = protectedEndpoints()
                .map(ProtectedEndpoint::handlerMethod)
                .collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(declared).hasSize(8);
    }

    /**
     * Verifies this controller has no unprotected endpoints. Nothing under {@code /photos} is
     * meant to be anonymous, and an endpoint appearing here would fall back to the path rule
     * alone — wider than every {@code @PreAuthorize} on the class, and invisible to the
     * completeness test above, which only inspects endpoints that ARE protected.
     */
    @Test
    @DisplayName("no endpoint on this controller is unprotected")
    void everyEndpointCarriesPreAuthorize() {
        assertThat(ControllerEndpoints.handlerMethodsMissingAuthorization(PhotoController.class))
                .isEmpty();
    }

    /** PhotoDTO is plain {@code @Data} with no builder, unlike the account and album DTOs. */
    private static PhotoDTO photoWithId(Long id) {
        PhotoDTO dto = new PhotoDTO();
        dto.setId(id);
        return dto;
    }

    private static MockHttpServletRequestBuilder asAdmin(HttpMethod method, String path) {
        return MockMvcRequestBuilders.request(method, path)
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.adminToken()));
    }

    /**
     * Blanket stubs for the matrix tests, which walk every endpoint and care only about the
     * status security produced. {@code create} and {@code findAll} dereference their result to
     * log ({@code getId()}, {@code getTotalElements()}), so Mockito's null default would turn an
     * authorized request into a 500 and mask the authorization outcome under assertion.
     */
    private void stubEverything() {
        when(photoService.create(any())).thenReturn(photoWithId(1L));
        when(photoService.findById(anyLong())).thenReturn(new PhotoDTO());
        when(photoService.findAll(any())).thenReturn(emptyPagedResponse());
        when(photoService.countByAlbumIdIn(any())).thenReturn(0L);
        when(photoService.update(anyLong(), any())).thenReturn(new PhotoDTO());
        when(photoService.activateOrDeactivate(anyLong(), anyBoolean())).thenReturn(new PhotoDTO());
    }
}
