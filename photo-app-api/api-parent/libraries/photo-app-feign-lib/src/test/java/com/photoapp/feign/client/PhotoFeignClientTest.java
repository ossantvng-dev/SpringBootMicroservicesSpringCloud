package com.photoapp.feign.client;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.harness.AbstractFeignClientTest;
import com.photoapp.feign.harness.DownstreamBodies;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.photoapp.commons.dto.PagedResponseDTO;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Success paths for {@link PhotoFeignClient}'s six methods.
 *
 * <p>This client is the outlier of the four: only two of its six methods carry any resilience
 * annotation, and in particular {@code findById} and {@code findAll} have none — while the
 * identically-shaped methods on {@link AlbumFeignClient} and {@link AccountFeignClient} both do.
 * {@link #findByIdIsUnprotectedUnlikeItsSiblingsOnTheOtherClients} asserts that asymmetry rather
 * than leaving it as something a reader has to notice.
 */
class PhotoFeignClientTest extends AbstractFeignClientTest {

    @Autowired
    private PhotoFeignClient photoFeignClient;

    /** Verifies {@code findById} deserialises a {@code PhotoDTO} from the per-id path. */
    @Test
    void findByIdDeserialisesThePhoto() {
        stubFor(get(urlEqualTo("/photos/99"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.PHOTO_DTO)));

        PhotoDTO photo = photoFeignClient.findById(99L);

        assertThat(photo).isNotNull();
        assertThat(photo.getId()).isEqualTo(99L);
        assertThat(photo.getAlbumId()).isEqualTo(11L);
        assertThat(photo.getFileName()).isEqualTo("note-g.png");
        assertThat(photo.getActivePhoto()).isTrue();
    }

    /** Verifies the paged listing deserialises and flattens its filter map. No caller today. */
    @Test
    void findAllDeserialisesAPageOfPhotos() {
        stubFor(get(urlPathEqualTo("/photos"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.pagedResponseOf(DownstreamBodies.PHOTO_DTO))));

        PagedResponseDTO<PhotoDTO> page = photoFeignClient.findAll(Map.of("albumId", "11"));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getFileName()).isEqualTo("note-g.png");
        verify(1, getRequestedFor(urlPathEqualTo("/photos"))
                .withQueryParam("albumId", equalTo("11")));
    }

    /** Verifies {@code deleteById} issues a DELETE to the per-id path. No caller today. */
    @Test
    void deleteByIdSendsADelete() {
        stubFor(delete(urlEqualTo("/photos/99"))
                .willReturn(aResponse().withStatus(204)));

        photoFeignClient.deleteById(99L);

        verify(1, deleteRequestedFor(urlEqualTo("/photos/99")));
    }

    /**
     * Verifies the album-id list is sent as repeated query parameters.
     *
     * <p>This is the deepest link in the user-deletion cascade — {@code UserServiceImpl} deletes
     * photos before albums before accounts, so a mis-encoded list here leaves orphaned photo rows
     * pointing at albums that are about to disappear, and the enclosing delete still reports
     * success.
     */
    @Test
    void deleteByAlbumIdsSendsRepeatedQueryParameters() {
        stubFor(delete(urlPathEqualTo("/photos/byAlbumIds"))
                .willReturn(aResponse().withStatus(204)));

        photoFeignClient.deleteByAlbumIds(List.of(11L, 12L));

        verify(1, deleteRequestedFor(urlPathEqualTo("/photos/byAlbumIds"))
                .withQueryParam("albumIds", equalTo("11"))
                .withQueryParam("albumIds", equalTo("12")));
    }

    /**
     * Verifies the photo count decodes as a {@code long} and sends every album id.
     *
     * <p>{@code AlbumServiceImpl} refuses to delete an album while this returns more than zero, so
     * as with {@code countByAccountId} the value is an interlock: a decoding failure reads as 0 and
     * silently permits the delete. A non-zero stub is what makes the assertion meaningful.
     */
    @Test
    void countByAlbumIdsDecodesANonZeroCount() {
        stubFor(get(urlPathEqualTo("/photos/countByAlbumIds"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("7")));

        assertThat(photoFeignClient.countByAlbumIds(List.of(11L, 12L))).isEqualTo(7L);

        verify(1, getRequestedFor(urlPathEqualTo("/photos/countByAlbumIds"))
                .withQueryParam("albumIds", equalTo("11"))
                .withQueryParam("albumIds", equalTo("12")));
    }

    /**
     * Verifies {@code findById} has no fallback, so a downstream failure surfaces with the
     * decoder's own status rather than 503.
     *
     * <p>The asymmetry is the finding. {@code AlbumFeignClient#findById} and
     * {@code AccountFeignClient#findById} are the same shape and both carry {@code @CircuitBreaker}
     * + {@code @Retry} + a fallback; this one carries nothing. So a photos-service outage is
     * reported to its callers differently from an albums-service outage, and no breaker ever opens
     * for it however long photos-service stays down.
     *
     * <p>Nothing calls it today, so nothing is broken right now — which is precisely why the
     * inconsistency would otherwise go unnoticed until the first caller appears. Pinning it makes
     * adding the annotations a deliberate act.
     *
     * <p>The single-request assertion doubles as the guard for
     * {@code FeignTransportAutoConfiguration}. HttpClient 5's
     * {@code DefaultHttpRequestRetryStrategy} retries 429 and 503 <em>responses</em> by default,
     * so before transport-level retries were disabled on 2026-08-07 this stub was fetched twice —
     * on a method with no {@code @Retry} at all. That is the clearest possible statement of the
     * problem: a retry nobody configured, on a path where retrying was explicitly not asked for.
     */
    @Test
    void findByIdIsUnprotectedUnlikeItsSiblingsOnTheOtherClients() {
        stubFor(get(urlEqualTo("/photos/99"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> photoFeignClient.findById(99L))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(breaker("photo-app-photos-service-findById").getMetrics().getNumberOfBufferedCalls())
                .as("no @CircuitBreaker on this method, so no call is ever recorded and the "
                        + "breaker can never open no matter how long photos-service is down")
                .isZero();

        verify(1, getRequestedFor(urlEqualTo("/photos/99")));
    }

    /** Verifies an unknown photo keeps its 404 — no fallback here, so the decoder's status stands. */
    @Test
    void unknownPhotoKeepsIts404() {
        stubFor(get(urlEqualTo("/photos/404"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> photoFeignClient.findById(404L))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
