package com.photoapp.photos.mapper;

import com.photoapp.entity.Photo;
import com.photoapp.photos.dto.CreatePhotoInputDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PhotoInputMapper} — the create-photo request → entity mapping.
 *
 * <p>The one mapper where the ignored field is a <strong>primitive</strong>:
 * {@code Photo.activePhoto} is {@code boolean}, not {@code Boolean}, unlike the equivalent field on
 * every other entity. That changes what the ignore means. On {@code Album} or {@code Account},
 * losing the {@code @Builder.Default} would produce a null and a NullPointerException at the first
 * guard — loud, and traceable. Here it produces {@code false}, which is a perfectly valid value:
 * every photo would be created deactivated, no exception anywhere, and the only symptom is photos
 * that do not appear.
 */
class PhotoInputMapperTest {

    private final PhotoInputMapper mapper = new PhotoInputMapperImpl();

    private static CreatePhotoInputDTO anInput() {
        CreatePhotoInputDTO input = new CreatePhotoInputDTO();
        input.setAlbumId(11L);
        input.setFileName("note-g.png");
        input.setFileUrl("https://example.invalid/note-g.png");
        return input;
    }

    /** Verifies all three request fields reach the entity. */
    @Test
    void everyMappedFieldReachesTheEntity() {
        Photo entity = mapper.toEntity(anInput());

        assertThat(entity.getAlbumId()).isEqualTo(11L);
        assertThat(entity.getFileName()).isEqualTo("note-g.png");
        assertThat(entity.getFileUrl()).isEqualTo("https://example.invalid/note-g.png");
    }

    /**
     * Verifies the ignored primitive {@code activePhoto} still ends up true.
     *
     * <p>The most valuable assertion in this class, precisely because its failure mode is quiet.
     * A primitive cannot be null, so if the {@code @Builder.Default} were ever removed this would
     * silently become {@code false} — a legal value, no exception, and every newly uploaded photo
     * invisible to the API that filters on {@code activePhoto}. There is no other signal.
     */
    @Test
    @DisplayName("ignored primitive activePhoto is true, not the default false")
    void activePhotoFallsBackToTrueRatherThanThePrimitiveDefault() {
        assertThat(mapper.toEntity(anInput()).isActivePhoto())
                .as("a primitive cannot be null, so losing @Builder.Default here yields false — "
                        + "a legal value, no exception, and every new photo silently invisible")
                .isTrue();
    }

    /** Verifies a null input maps to null rather than throwing. */
    @Test
    void aNullInputMapsToNull() {
        assertThat(mapper.toEntity(null)).isNull();
    }

    /** Verifies unset optional fields stay null rather than becoming empty strings. */
    @Test
    void unsetFieldsStayNull() {
        CreatePhotoInputDTO sparse = new CreatePhotoInputDTO();
        sparse.setAlbumId(11L);

        Photo entity = mapper.toEntity(sparse);

        assertThat(entity.getAlbumId()).isEqualTo(11L);
        assertThat(entity.getFileName()).isNull();
        assertThat(entity.getFileUrl()).isNull();
    }

    /** The Step 5 guard for this module's mapper. See {@code MapperConventionsTest} in commons. */
    @Test
    @DisplayName("PhotoInputMapper still declares unmappedTargetPolicy = ERROR")
    void theMapperStillFailsTheBuildOnAnUnmappedTarget() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "photoapp", "photos", "mapper", "PhotoInputMapper.java"));

        assertThat(source.replaceAll("\\s+", ""))
                .contains("unmappedTargetPolicy=ReportingPolicy.ERROR");
    }
}
