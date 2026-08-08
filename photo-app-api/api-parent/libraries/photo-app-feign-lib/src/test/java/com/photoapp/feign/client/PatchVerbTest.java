package com.photoapp.feign.client;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.feign.harness.AbstractFeignClientTest;
import com.photoapp.feign.harness.DownstreamBodies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION GUARD for the PATCH defect found and fixed on 2026-08-07.
 * See backlog.txt, "PATCH is unreachable over Feign's default client".
 *
 * <p>Until the five services declared {@code io.github.openfeign:feign-hc5}, none of the three
 * {@code activateOrDeactivate} methods could issue a request at all. Feign fell back to
 * {@code feign.Client$Default}, which is {@code java.net.HttpURLConnection}, and that class
 * rejects PATCH from a fixed method whitelist with
 * {@code ProtocolException: Invalid HTTP method: PATCH} — thrown in the client, before a socket
 * was opened, so the downstream never saw anything and logged nothing.
 *
 * <p>This class was a characterization test pinning that failure. It now asserts the opposite, and
 * the two assertions per case are what make it a real guard rather than a smoke test: the request
 * <em>reaches WireMock</em> as a PATCH with the right query parameter, and the response
 * deserialises. Dropping {@code feign-hc5} from any of the five poms puts the whole suite back to
 * {@code Client$Default} and breaks these three immediately.
 *
 * <p>The transport is selected purely by classpath presence — Spring Cloud's
 * {@code HttpClient5FeignLoadBalancerConfiguration} is {@code @ConditionalOnClass(ApacheHttp5Client)}
 * — so there is no bean and no property to check, and nothing that would fail loudly if the
 * dependency vanished. These tests are the only thing that would notice.
 */
class PatchVerbTest extends AbstractFeignClientTest {

    @Autowired
    private AccountFeignClient accountFeignClient;

    @Autowired
    private AlbumFeignClient albumFeignClient;

    @Autowired
    private PhotoFeignClient photoFeignClient;

    /**
     * Verifies {@code PATCH /accounts/{id}/activate} now reaches the downstream and returns the
     * updated account.
     *
     * <p>The {@code activate=false} query parameter is asserted alongside the verb because the
     * flag is the entire payload — a PATCH that arrived without it, or with the wrong value, would
     * deactivate an account the caller meant to activate, and the status would still be 200.
     */
    @Test
    @DisplayName("PATCH /accounts/{id}/activate reaches the downstream")
    void accountsActivateSendsAPatch() {
        stubFor(patch(urlPathEqualTo("/accounts/7/activate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.ACCOUNT_DTO)));

        AccountDTO account = accountFeignClient.activateOrDeactivate(7L, false);

        assertThat(account).isNotNull();
        assertThat(account.getId()).isEqualTo(7L);
        verify(1, patchRequestedFor(urlPathEqualTo("/accounts/7/activate"))
                .withQueryParam("activate", equalTo("false")));
    }

    /** Verifies {@code PATCH /albums/{id}/activate} reaches the downstream. Same fix, same guard. */
    @Test
    @DisplayName("PATCH /albums/{id}/activate reaches the downstream")
    void albumsActivateSendsAPatch() {
        stubFor(patch(urlPathEqualTo("/albums/11/activate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.ALBUM_DTO)));

        AlbumDTO album = albumFeignClient.activateOrDeactivate(11L, true);

        assertThat(album).isNotNull();
        assertThat(album.getId()).isEqualTo(11L);
        verify(1, patchRequestedFor(urlPathEqualTo("/albums/11/activate"))
                .withQueryParam("activate", equalTo("true")));
    }

    /** Verifies {@code PATCH /photos/{id}/activate} reaches the downstream. Same fix, same guard. */
    @Test
    @DisplayName("PATCH /photos/{id}/activate reaches the downstream")
    void photosActivateSendsAPatch() {
        stubFor(patch(urlPathEqualTo("/photos/99/activate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.PHOTO_DTO)));

        PhotoDTO photo = photoFeignClient.activateOrDeactivate(99L, true);

        assertThat(photo).isNotNull();
        assertThat(photo.getId()).isEqualTo(99L);
        verify(1, patchRequestedFor(urlPathEqualTo("/photos/99/activate"))
                .withQueryParam("activate", equalTo("true")));
    }

    /**
     * Verifies a PATCH that fails downstream fails for a <em>downstream</em> reason, with the
     * request actually delivered.
     *
     * <p>The distinction the old defect blurred. A transport-level rejection and a downstream 404
     * both surface to the caller as an exception; only one of them means the callee was consulted.
     * Asserting that WireMock recorded the request is what separates them, and it is the assertion
     * that would have caught the original bug had it existed then — the characterization test this
     * class replaces proved the journal was <em>empty</em>.
     */
    @Test
    void aFailingPatchStillReachesTheDownstream() {
        stubFor(patch(urlPathEqualTo("/accounts/404/activate"))
                .willReturn(aResponse().withStatus(404)));

        try {
            accountFeignClient.activateOrDeactivate(404L, true);
        } catch (RuntimeException expected) {
            // the point is where it failed, not that it failed
        }

        assertThat(DOWNSTREAM.getAllServeEvents())
                .as("a PATCH must fail at the downstream, not in the client — an empty request "
                        + "journal is the signature of the 2026-08-07 transport defect")
                .hasSize(1);
    }
}
