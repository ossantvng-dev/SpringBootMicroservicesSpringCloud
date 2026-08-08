package com.photoapp.feign.interceptor;

import com.photoapp.feign.client.UserFeignClient;
import com.photoapp.feign.harness.AbstractFeignClientTest;
import com.photoapp.feign.harness.DownstreamBodies;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link FeignAuthInterceptor} — the component that decides whether an inter-service call carries a
 * credential.
 *
 * <p>Its behaviour is the direct cause of the 2026-08-05 {@code /auth/refresh} defect. It forwards
 * the <em>inbound</em> Authorization header and nothing else, so a Feign call made on a request
 * that had no token reaches the downstream anonymous. That was fine everywhere except refresh,
 * which by definition is called without a usable access token, and which was calling the protected
 * {@code GET /users/{id}} — the downstream answered 401, the fallback reported 503, and the fix
 * was to stop calling a protected endpoint rather than to change this class. See backlog.txt.
 *
 * <p>So the no-credential path below is not an edge case to be tolerated; it is the documented
 * behaviour the rest of the system now has to be designed around, and pinning it means a future
 * "let's just add a service account here" change is a visible decision.
 */
class FeignAuthInterceptorTest extends AbstractFeignClientTest {

    private final FeignAuthInterceptor interceptor = new FeignAuthInterceptor();

    @Autowired
    private UserFeignClient userFeignClient;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void bindRequestWith(String authorizationHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** Verifies the inbound Authorization header is copied verbatim onto the outbound template. */
    @Test
    void theInboundAuthorizationHeaderIsForwarded() {
        bindRequestWith("Bearer inbound-token");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).containsKey(HttpHeaders.AUTHORIZATION);
        assertThat(template.headers().get(HttpHeaders.AUTHORIZATION))
                .as("the token must cross unchanged — the downstream validates the same signature")
                .containsExactly("Bearer inbound-token");
    }

    /**
     * Verifies the interceptor no-ops when there is no request context at all, rather than throwing.
     *
     * <p>This is the case that matters for anything not driven by an HTTP request: a scheduled job,
     * an async task, a message listener, or application startup. {@code RequestContextHolder}
     * returns null there, and an unguarded {@code attributes.getRequest()} would be a
     * {@code NullPointerException} thrown from inside a Feign interceptor — surfacing as a
     * "downstream call failed" that has nothing to do with the downstream.
     *
     * <p>The absence of the header is asserted alongside the absence of an exception, because
     * "didn't throw" alone would also be satisfied by an interceptor that attached something
     * meaningless.
     */
    @Test
    @DisplayName("no request context: no header, no exception")
    void withoutARequestContextItDoesNothing() {
        RequestContextHolder.resetRequestAttributes();
        RequestTemplate template = new RequestTemplate();

        assertThatCode(() -> interceptor.apply(template))
                .as("a Feign call from a scheduled job or a message listener has no request "
                        + "context; throwing here would look like a downstream failure")
                .doesNotThrowAnyException();

        assertThat(template.headers()).doesNotContainKey(HttpHeaders.AUTHORIZATION);
    }

    /**
     * Verifies an inbound request with no Authorization header produces an outbound call with none
     * either — the shape behind the 2026-08-05 refresh defect.
     *
     * <p>{@code POST /auth/refresh} is {@code permitAll} and callers reach it precisely because
     * their access token is gone, so there is nothing to forward. The interceptor correctly attaches
     * nothing; the bug was that the downstream endpoint being called required a credential. Pinning
     * this makes the constraint explicit: <strong>any Feign call reachable from a public endpoint
     * must target a public downstream endpoint</strong>, because this class will not invent a
     * credential.
     */
    @Test
    void anInboundRequestWithoutATokenForwardsNothing() {
        bindRequestWith(null);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers())
                .as("there is no service-to-service credential anywhere in this system; a call "
                        + "made without an inbound token arrives at the downstream anonymous")
                .doesNotContainKey(HttpHeaders.AUTHORIZATION);
    }

    /**
     * Verifies the header actually reaches the downstream over a real Feign call, not just the
     * template.
     *
     * <p>The unit tests above prove the interceptor's logic; this proves it is <em>installed</em>.
     * {@code FeignConfiguration} declares it as a {@code @Bean}, and Feign only picks up a
     * {@code RequestInterceptor} bean that lands in the client's own child context — a wiring step
     * that no amount of testing {@code apply()} directly would catch if it broke.
     */
    @Test
    void theHeaderReachesTheDownstreamOverARealCall() {
        bindRequestWith("Bearer end-to-end-token");
        stubFor(get(urlEqualTo("/users/42/active"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("true")));

        assertThat(userFeignClient.isActive(42L)).isTrue();

        verify(1, getRequestedFor(urlEqualTo("/users/42/active"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer end-to-end-token")));
    }

    /**
     * Verifies a real call made with no request context reaches the downstream with no
     * Authorization header — the anonymous outbound call, observed at the wire rather than inferred.
     *
     * <p>Asserting the header is <em>absent</em> rather than merely not-forwarded rules out the
     * other way this could go wrong: a stale value left on a pooled template or carried over from a
     * previous request on the same thread would be a credential leak between callers, and would
     * look identical from inside {@code apply()}.
     */
    @Test
    void arealCallWithoutARequestContextArrivesAnonymous() {
        RequestContextHolder.resetRequestAttributes();
        stubFor(get(urlEqualTo("/users/42"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.USER_DTO)));

        userFeignClient.findById(42L);

        verify(1, getRequestedFor(urlEqualTo("/users/42"))
                .withHeader(HttpHeaders.AUTHORIZATION, absent()));
    }
}
