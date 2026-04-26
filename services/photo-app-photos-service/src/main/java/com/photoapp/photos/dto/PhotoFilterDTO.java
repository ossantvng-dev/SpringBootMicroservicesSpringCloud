package com.photoapp.photos.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhotoFilterDTO {
    private Long albumId;
    private String fileName;
    private Boolean activePhoto;
    private LocalDateTime createdStart;
    private LocalDateTime createdEnd;
    private LocalDateTime updatedStart;
    private LocalDateTime updatedEnd;
}
