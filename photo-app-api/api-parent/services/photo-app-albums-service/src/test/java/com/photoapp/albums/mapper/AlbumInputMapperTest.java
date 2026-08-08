package com.photoapp.albums.mapper;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.entity.Album;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AlbumInputMapper} — the create-album request → entity mapping.
 *
 * <p>One ignore, {@code activeAlbum}, left to Lombok's {@code @Builder.Default} of true. Note that
 * {@code AlbumServiceImpl} then sets it explicitly anyway — belt and braces that hides how much
 * the default is doing, which is why {@link #activeAlbumFallsBackToTheBuilderDefault} asserts the
 * mapper's own output rather than the service's.
 */
class AlbumInputMapperTest {

    private final AlbumInputMapper mapper = new AlbumInputMapperImpl();

    private static CreateAlbumInputDTO anInput() {
        CreateAlbumInputDTO input = new CreateAlbumInputDTO();
        input.setAccountId(7L);
        input.setTitle("Analytical Engine");
        input.setDescription("notes and diagrams");
        return input;
    }

    /**
     * Verifies all three request fields reach the entity.
     *
     * <p>{@code accountId} is the one that matters: it is what ties the album to an account, and
     * {@code AlbumServiceImpl} overwrites it with {@code account.getId()} immediately after
     * mapping. That overwrite means a broken mapping here would be invisible in production — which
     * is exactly why it is asserted at the mapper.
     */
    @Test
    void everyMappedFieldReachesTheEntity() {
        Album entity = mapper.toEntity(anInput());

        assertThat(entity.getAccountId()).isEqualTo(7L);
        assertThat(entity.getTitle()).isEqualTo("Analytical Engine");
        assertThat(entity.getDescription()).isEqualTo("notes and diagrams");
    }

    /**
     * Verifies {@code activeAlbum} falls back to the builder default of true.
     *
     * <p>Asserted here even though the service sets it explicitly, because the ignore is only safe
     * while the default exists. Remove {@code @Builder.Default} from the entity and this becomes
     * null — harmless on the create path that overwrites it, and a NullPointerException on any
     * other caller that ever uses this mapper.
     */
    @Test
    @DisplayName("ignored activeAlbum falls back to @Builder.Default(true), not null")
    void activeAlbumFallsBackToTheBuilderDefault() {
        assertThat(mapper.toEntity(anInput()).getActiveAlbum()).isTrue();
    }

    /** Verifies a null input maps to null rather than throwing. */
    @Test
    void aNullInputMapsToNull() {
        assertThat(mapper.toEntity(null)).isNull();
    }

    /** Verifies an omitted optional description stays null rather than becoming empty. */
    @Test
    void anOmittedDescriptionStaysNull() {
        CreateAlbumInputDTO sparse = new CreateAlbumInputDTO();
        sparse.setAccountId(7L);
        sparse.setTitle("untitled");

        assertThat(mapper.toEntity(sparse).getDescription()).isNull();
    }

    /** The Step 5 guard for this module's mapper. See {@code MapperConventionsTest} in commons. */
    @Test
    @DisplayName("AlbumInputMapper still declares unmappedTargetPolicy = ERROR")
    void theMapperStillFailsTheBuildOnAnUnmappedTarget() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "photoapp", "albums", "mapper", "AlbumInputMapper.java"));

        assertThat(source.replaceAll("\\s+", ""))
                .contains("unmappedTargetPolicy=ReportingPolicy.ERROR");
    }
}
