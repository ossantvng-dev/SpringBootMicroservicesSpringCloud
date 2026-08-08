package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.entity.Photo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PhotoMapper} — one method, and one type mismatch worth knowing about.
 *
 * <p>{@code Photo.activePhoto} is a primitive {@code boolean} while {@code PhotoDTO.activePhoto} is
 * a boxed {@code Boolean}. Every other entity in the project uses the boxed form on both sides.
 * MapStruct autoboxes silently, so the asymmetry is invisible in the mapper — but it means the
 * entity cannot represent "unknown" while the DTO can, and the DTO→entity direction (which does not
 * exist here, but does on {@code PhotoInputMapper}) would unbox a null into a
 * NullPointerException.
 */
class PhotoMapperTest {

    private final PhotoMapper mapper = new PhotoMapperImpl();

    private static Photo aPhoto() {
        Photo photo = Photo.builder()
                .albumId(11L)
                .fileName("note-g.png")
                .fileUrl("https://example.invalid/note-g.png")
                .activePhoto(true)
                .build();
        photo.setId(99L);
        photo.setVersion(4L);
        return photo;
    }

    /** Verifies every {@code PhotoDTO} field is populated from its matching entity field. */
    @Test
    void everyFieldMapsToTheDto() {
        PhotoDTO dto = mapper.toDTO(aPhoto());

        assertThat(dto.getId()).isEqualTo(99L);
        assertThat(dto.getVersion()).isEqualTo(4L);
        assertThat(dto.getAlbumId()).isEqualTo(11L);
        assertThat(dto.getFileName()).isEqualTo("note-g.png");
        assertThat(dto.getFileUrl()).isEqualTo("https://example.invalid/note-g.png");
        assertThat(dto.getActivePhoto()).isTrue();
        assertThat(dto.getCreatedAt()).isNotNull();
        assertThat(dto.getUpdatedAt()).isNotNull();
    }

    /**
     * Verifies the primitive {@code boolean} boxes to {@code Boolean.FALSE}, not to null.
     *
     * <p>The one place the primitive/boxed asymmetry could bite. A {@code false} that arrived as
     * null would pass any {@code != Boolean.TRUE} check and fail a {@code .equals(false)} one, so
     * the two would disagree about the same photo.
     */
    @Test
    @DisplayName("primitive false boxes to Boolean.FALSE, not null")
    void aDeactivatedPhotoBoxesToFalseNotNull() {
        Photo photo = aPhoto();
        photo.setActivePhoto(false);

        assertThat(mapper.toDTO(photo).getActivePhoto())
                .isNotNull()
                .isFalse();
    }

    /** Verifies a null entity maps to a null DTO rather than throwing. */
    @Test
    void aNullPhotoMapsToNull() {
        assertThat(mapper.toDTO(null)).isNull();
    }
}
