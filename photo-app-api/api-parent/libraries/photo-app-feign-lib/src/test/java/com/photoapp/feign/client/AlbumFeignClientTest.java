package com.photoapp.feign.client;

import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.harness.AbstractFeignClientTest;
import com.photoapp.feign.harness.DownstreamBodies;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
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
 * Success paths for {@link AlbumFeignClient}'s six methods.
 *
 * <p>Two of them are the ones that make deletes correct across service boundaries:
 * {@code countByAccountId} is the guard {@code AccountServiceImpl} consults before deleting an
 * account, and {@code deleteByAccountIds} is the cascade {@code UserServiceImpl} performs. Both
 * take collections or produce counts, where an encoding mistake is silent rather than loud — a
 * count that decodes as 0 permits a delete that should have been refused.
 */
class AlbumFeignClientTest extends AbstractFeignClientTest {

    @Autowired
    private AlbumFeignClient albumFeignClient;

    /**
     * Verifies {@code findById} deserialises an {@code AlbumDTO} from the per-id path.
     *
     * <p>{@code PhotoServiceImpl} calls this on seven separate paths, always to read
     * {@code accountId} so it can then resolve the owning account. So {@code accountId} is asserted
     * explicitly: if it arrived null, every ownership check in photos-service would dereference it.
     */
    @Test
    void findByIdDeserialisesTheAlbum() {
        stubFor(get(urlEqualTo("/albums/11"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.ALBUM_DTO)));

        AlbumDTO album = albumFeignClient.findById(11L);

        assertThat(album).isNotNull();
        assertThat(album.getId()).isEqualTo(11L);
        assertThat(album.getAccountId())
                .as("PhotoServiceImpl chains findById(album.getAccountId()) straight off this field")
                .isEqualTo(7L);
        assertThat(album.getTitle()).isEqualTo("Analytical Engine");
        assertThat(album.getActiveAlbum()).isTrue();
    }

    /**
     * Verifies the paged listing deserialises and flattens its filter map.
     *
     * <p>Called from {@code UserServiceImpl#deleteById}, which uses it to find the albums belonging
     * to a user's accounts before deleting their photos. It is the second hop of the delete
     * cascade, so a decoding failure here strands photo rows under albums that are about to
     * disappear.
     */
    @Test
    void findAllDeserialisesAPageOfAlbums() {
        stubFor(get(urlPathEqualTo("/albums"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.pageOf(DownstreamBodies.ALBUM_DTO))));

        Page<AlbumDTO> page = albumFeignClient.findAll(Map.of("accountId", "7"));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getTitle()).isEqualTo("Analytical Engine");
        verify(1, getRequestedFor(urlPathEqualTo("/albums"))
                .withQueryParam("accountId", equalTo("7")));
    }

    /** Verifies {@code deleteById} issues a DELETE to the per-id path. No caller today. */
    @Test
    void deleteByIdSendsADelete() {
        stubFor(delete(urlEqualTo("/albums/11"))
                .willReturn(aResponse().withStatus(204)));

        albumFeignClient.deleteById(11L);

        verify(1, deleteRequestedFor(urlEqualTo("/albums/11")));
    }

    /**
     * Verifies a list of account ids is sent as repeated query parameters, not as one joined string.
     *
     * <p>This is the encoding that actually matters. {@code UserServiceImpl} passes every account id
     * belonging to a deleted user in one call; if Feign emitted {@code accountIds=7,8,9} the
     * receiving controller would bind a single malformed value, and the 2026-08-05
     * {@code AlbumSpecification} defect showed exactly what that produces — an unguarded parse and a
     * 500. Repeated parameters are what a {@code List<Long>} binds from.
     */
    @Test
    void deleteByAccountIdsSendsRepeatedQueryParameters() {
        stubFor(delete(urlPathEqualTo("/albums/byAccountIds"))
                .willReturn(aResponse().withStatus(204)));

        albumFeignClient.deleteByAccountIds(List.of(7L, 8L, 9L));

        verify(1, deleteRequestedFor(urlPathEqualTo("/albums/byAccountIds"))
                .withQueryParam("accountIds", equalTo("7"))
                .withQueryParam("accountIds", equalTo("8"))
                .withQueryParam("accountIds", equalTo("9")));
    }

    /**
     * Verifies the album count decodes as a {@code long} from a bare JSON number.
     *
     * <p>{@code AccountServiceImpl} deletes an account only when this returns 0, so the value is a
     * safety interlock, not information. A primitive {@code long} cannot represent "unknown", which
     * means any decoding failure would present as 0 — permission to delete an account that still
     * has albums. Asserting a non-zero value is the point: a test that stubbed 0 would pass against
     * a client that had stopped reading the body at all.
     */
    @Test
    void countByAccountIdDecodesANonZeroCount() {
        stubFor(get(urlPathEqualTo("/albums/countByAccountId"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("3")));

        assertThat(albumFeignClient.countByAccountId(7L)).isEqualTo(3L);

        verify(1, getRequestedFor(urlPathEqualTo("/albums/countByAccountId"))
                .withQueryParam("accountId", equalTo("7")));
    }

    /** Verifies a genuine zero is carried through as zero, so the interlock can actually open. */
    @Test
    void countByAccountIdCarriesAGenuineZeroThrough() {
        stubFor(get(urlPathEqualTo("/albums/countByAccountId"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("0")));

        assertThat(albumFeignClient.countByAccountId(7L)).isZero();
    }

    /** Verifies an unknown album keeps its 404 rather than being flattened to 503. */
    @Test
    void unknownAlbumKeepsIts404RatherThanBecoming503() {
        stubFor(get(urlEqualTo("/albums/404"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> albumFeignClient.findById(404L))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
