package com.photoapp.feign.client;

import com.photoapp.feign.harness.AbstractFeignClientTest;
import com.photoapp.feign.harness.DownstreamBodies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CHARACTERIZATION TEST — pins a defect rather than asserting correct behaviour.
 * See backlog.txt, "PATCH is unreachable over Feign's default client" (2026-08-07).
 *
 * <p>All three {@code activateOrDeactivate} methods are {@code @PatchMapping}, and none of them can
 * issue a request at all. Feign falls back to {@code feign.Client$Default}, which is
 * {@code java.net.HttpURLConnection}, and that class rejects PATCH from its fixed method whitelist
 * with {@code ProtocolException: Invalid HTTP method: PATCH}. The failure is in the client, before
 * a socket is opened — WireMock never sees a request, which is what the zero-request assertion
 * below proves.
 *
 * <p><strong>This is not a harness artefact.</strong> Spring Cloud only swaps the transport when
 * {@code feign.hc5.ApacheHttp5Client} is on the classpath — plain {@code httpclient5} does not
 * satisfy {@code HttpClient5FeignLoadBalancerConfiguration}'s {@code @ConditionalOnClass}. Checked
 * on 2026-08-07 inside all five running service images: {@code feign-hc5} is absent from every one,
 * there is no okhttp or http2-client either, and no module defines a {@code feign.Client} bean. So
 * production runs the same {@code Client$Default} this test does. This library therefore
 * deliberately does <em>not</em> put a PATCH-capable transport on the test classpath; doing so
 * would make these three methods pass here and still fail in production.
 *
 * <p>It has stayed invisible because <strong>nothing calls any of the three</strong> — they are
 * three of the eleven Feign methods with no call site anywhere in the reactor. The first caller
 * would get a 500 that looks like a downstream fault and is not one.
 *
 * <p>Not fixed here: the fix is a production dependency change with a blast radius across all five
 * services (every Feign call would move to a new transport), which is well outside a testing phase.
 * Logged in backlog.txt with the two options — add {@code feign-hc5}, or change the three methods
 * to {@code PUT} to match what the transport supports.
 */
class PatchVerbCharacterizationTest extends AbstractFeignClientTest {

    @Autowired
    private AccountFeignClient accountFeignClient;

    @Autowired
    private AlbumFeignClient albumFeignClient;

    @Autowired
    private PhotoFeignClient photoFeignClient;

    /**
     * Characterises the accounts PATCH: it fails in the client and never reaches the network.
     *
     * <p>The stub is registered and would answer 200, so the only reason the call fails is the verb.
     * Asserting that WireMock received nothing is what separates "the request was rejected" from
     * "the request was never sent" — only the second explains why no downstream log line ever
     * appears when this is called.
     */
    @Test
    @DisplayName("PATCH /accounts/{id}/activate never leaves the client")
    void accountsActivateCannotSendPatch() {
        stubFor(patch(urlPathEqualTo("/accounts/7/activate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.ACCOUNT_DTO)));

        assertThatThrownBy(() -> accountFeignClient.activateOrDeactivate(7L, false))
                .as("CHARACTERIZATION: if this now passes, PATCH became reachable — a "
                        + "PATCH-capable transport was added. Delete this test and assert the "
                        + "success path instead.")
                .rootCause()
                .isInstanceOf(java.net.ProtocolException.class)
                .hasMessageContaining("Invalid HTTP method: PATCH");

        assertThat(DOWNSTREAM.getAllServeEvents())
                .as("the failure is client-side: nothing was ever sent")
                .isEmpty();
    }

    /** Characterises the albums PATCH — same defect, same cause. See the class Javadoc. */
    @Test
    @DisplayName("PATCH /albums/{id}/activate never leaves the client")
    void albumsActivateCannotSendPatch() {
        stubFor(patch(urlPathEqualTo("/albums/11/activate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.ALBUM_DTO)));

        assertThatThrownBy(() -> albumFeignClient.activateOrDeactivate(11L, true))
                .rootCause()
                .isInstanceOf(java.net.ProtocolException.class)
                .hasMessageContaining("Invalid HTTP method: PATCH");

        assertThat(DOWNSTREAM.getAllServeEvents()).isEmpty();
    }

    /** Characterises the photos PATCH — same defect, same cause. See the class Javadoc. */
    @Test
    @DisplayName("PATCH /photos/{id}/activate never leaves the client")
    void photosActivateCannotSendPatch() {
        stubFor(patch(urlPathEqualTo("/photos/99/activate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.PHOTO_DTO)));

        assertThatThrownBy(() -> photoFeignClient.activateOrDeactivate(99L, true))
                .rootCause()
                .isInstanceOf(java.net.ProtocolException.class)
                .hasMessageContaining("Invalid HTTP method: PATCH");

        assertThat(DOWNSTREAM.getAllServeEvents()).isEmpty();
    }
}
