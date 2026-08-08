package com.photoapp.feign.client;

import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.entity.User;
import com.photoapp.feign.harness.AbstractFeignClientTest;
import com.photoapp.feign.harness.DownstreamBodies;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Success paths for {@link UserFeignClient}'s three methods.
 *
 * <p>Every assertion here covers two things at once that are easy to conflate: the request Feign
 * <em>sent</em> (verb, path, path-variable substitution) and the response it <em>deserialised</em>.
 * The second is not free. {@code findByUsernameAndActiveUser} returns a JPA {@code @Entity} across
 * a service boundary, which is unusual enough to be worth pinning field by field; the other two
 * return DTOs.
 *
 * <p>Resilience behaviour for these methods lives in
 * {@code com.photoapp.feign.resilience.FeignResilienceMatrixTest}, which asserts the same five
 * properties uniformly across all twelve protected methods rather than repeating them per client.
 */
class UserFeignClientTest extends AbstractFeignClientTest {

    @Autowired
    private UserFeignClient userFeignClient;

    /**
     * Verifies {@code isActive} substitutes the id into the path and reads a bare JSON boolean.
     *
     * <p>The primitive return is the interesting part: a {@code boolean} cannot express "no answer",
     * so any decoding failure surfaces as {@code false} rather than as an error — and
     * {@code AccountServiceImpl} guards account creation with {@code if (isActive(...))}. A silent
     * {@code false} there rejects a legitimate user; a silent {@code true} would let a deactivated
     * one through.
     */
    @Test
    void isActiveReadsABooleanFromThePerIdPath() {
        stubFor(get(urlEqualTo("/users/42/active"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("true")));

        assertThat(userFeignClient.isActive(42L)).isTrue();
        verify(1, getRequestedFor(urlEqualTo("/users/42/active")));
    }

    /** Verifies a downstream {@code false} is carried through as {@code false}, not swallowed. */
    @Test
    void isActiveCarriesAFalseAnswerThrough() {
        stubFor(get(urlEqualTo("/users/42/active"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("false")));

        assertThat(userFeignClient.isActive(42L)).isFalse();
    }

    /**
     * Verifies the username lookup hits {@code /users/username/{username}} and materialises a full
     * {@code User}.
     *
     * <p>This is the single most load-bearing Feign call in the system: {@code CustomUserDetailsService},
     * {@code AuthorizationServiceImpl#login} and {@code TokenHandlerServiceImpl#refreshToken} all go
     * through it, so nobody logs in or refreshes if it breaks. It is also the endpoint the
     * 2026-08-05 refresh fix moved to <em>because</em> it is public — see backlog.txt.
     *
     * <p>{@code activeUser} is asserted explicitly rather than left to the object comparison: it is
     * the field the login flow branches on, and a boxed {@code Boolean} that silently arrived null
     * would throw a {@code NullPointerException} deep inside the auth service rather than here.
     */
    @Test
    void findByUsernameAndActiveUserDeserialisesTheWholeUser() {
        stubFor(get(urlEqualTo("/users/username/ada"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.USER)));

        User user = userFeignClient.findByUsernameAndActiveUser("ada");

        assertThat(user).isNotNull();
        assertThat(user.getId()).isEqualTo(42L);
        assertThat(user.getUsername()).isEqualTo("ada");
        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(user.getActiveUser()).isTrue();
        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$hashed");
        verify(1, getRequestedFor(urlEqualTo("/users/username/ada")));
    }

    /**
     * Verifies {@code findById} deserialises a {@code UserDTO} from {@code /users/{id}}.
     *
     * <p>Worth knowing while reading this: <strong>nothing calls this method.</strong> It was the
     * call the 2026-08-05 refresh fix removed — {@code /users/{id}} is {@code @PreAuthorize}-protected
     * and a refreshing client has no token to forward, which is what produced the 503. The method,
     * its fallback and its circuit-breaker instance in the config repo all survived the fix. Tested
     * anyway, because an untested dead method is the one that gets called next.
     */
    @Test
    void findByIdDeserialisesAUserDto() {
        stubFor(get(urlEqualTo("/users/42"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.USER_DTO)));

        UserDTO dto = userFeignClient.findById(42L);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(42L);
        assertThat(dto.getUsername()).isEqualTo("ada");
        assertThat(dto.getActiveUser()).isTrue();
    }

    /**
     * Verifies an unknown username surfaces as a 404, not as "users-service is unavailable".
     *
     * <p>Regression guard for the misleading-503 half of the 2026-08-05 fix. This is the exact call
     * a mistyped login makes, and before {@code FeignFallbacks.translate} existed the fallback
     * replaced the downstream's honest 404 with a blanket {@code SERVICE_UNAVAILABLE}, telling the
     * operator a healthy service was down. The status is asserted, not just the exception type,
     * because the type was always right — the status was the bug.
     */
    @Test
    void unknownUsernameKeepsIts404RatherThanBecoming503() {
        stubFor(get(urlEqualTo("/users/username/nobody"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> userFeignClient.findByUsernameAndActiveUser("nobody"))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
