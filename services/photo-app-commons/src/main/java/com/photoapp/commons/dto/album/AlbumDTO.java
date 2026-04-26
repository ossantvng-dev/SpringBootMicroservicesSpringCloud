package com.photoapp.commons.dto.album;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlbumDTO {
    private Long id;
    private Long accountId;
    private String title;
    private String description;
    private Boolean activeAlbum;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
