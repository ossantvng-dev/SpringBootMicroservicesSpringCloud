package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.entity.Album;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AlbumMapper} — one method, every field matched by name.
 *
 * <p>Nothing here needs an explicit {@code @Mapping}, which is exactly why it is worth an
 * assertion per field rather than an object comparison: the mapping is entirely implicit, so a
 * renamed field on either side is caught by {@code ReportingPolicy.ERROR} only if the rename makes
 * a target <em>unmapped</em>. Rename {@code title} on both sides in different ways and it still
 * compiles.
 *
 * <p>{@code accountId} carries the most weight. {@code PhotoServiceImpl} reads it off this DTO on
 * seven separate paths to resolve the owning account, so a null there is a NullPointerException in
 * a service that did nothing wrong.
 */
class AlbumMapperTest {

    private final AlbumMapper mapper = new AlbumMapperImpl();

    private static Album anAlbum() {
        Album album = Album.builder()
                .accountId(7L)
                .title("Analytical Engine")
                .description("notes and diagrams")
                .activeAlbum(true)
                .build();
        album.setId(11L);
        album.setVersion(2L);
        return album;
    }

    /** Verifies every {@code AlbumDTO} field is populated from its matching entity field. */
    @Test
    void everyFieldMapsToTheDto() {
        AlbumDTO dto = mapper.toDTO(anAlbum());

        assertThat(dto.getId()).isEqualTo(11L);
        assertThat(dto.getVersion()).isEqualTo(2L);
        assertThat(dto.getAccountId())
                .as("PhotoServiceImpl chains accountFeignClient.findById(album.getAccountId())")
                .isEqualTo(7L);
        assertThat(dto.getTitle()).isEqualTo("Analytical Engine");
        assertThat(dto.getDescription()).isEqualTo("notes and diagrams");
        assertThat(dto.getActiveAlbum()).isTrue();
        assertThat(dto.getCreatedAt()).isNotNull();
        assertThat(dto.getUpdatedAt()).isNotNull();
    }

    /**
     * Verifies {@code activeAlbum} carries a genuine false through.
     *
     * <p>Asserted separately from the true case because a mapper that dropped the field entirely
     * would leave the DTO's {@code Boolean} null, and a null reads as "not true" at most call
     * sites — so the false case would appear to work while the true case silently deactivated
     * every album.
     */
    @Test
    void aDeactivatedAlbumMapsAsInactiveRatherThanNull() {
        Album album = anAlbum();
        album.setActiveAlbum(false);

        assertThat(mapper.toDTO(album).getActiveAlbum()).isFalse();
    }

    /** Verifies an optional field left null maps to null rather than to an empty string. */
    @Test
    void aNullDescriptionStaysNull() {
        Album album = anAlbum();
        album.setDescription(null);

        assertThat(mapper.toDTO(album).getDescription()).isNull();
    }

    /** Verifies a null entity maps to a null DTO rather than throwing. */
    @Test
    void aNullAlbumMapsToNull() {
        assertThat(mapper.toDTO(null)).isNull();
    }
}
