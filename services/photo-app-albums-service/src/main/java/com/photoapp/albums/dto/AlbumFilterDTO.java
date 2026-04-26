package com.photoapp.albums.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlbumFilterDTO {
    private Long accountId;
    private String title;
    private String description;
    private Boolean activeAlbum;
}
