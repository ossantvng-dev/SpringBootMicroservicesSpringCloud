package com.photoapp.commons.dto.photo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PhotoDTO {
    private Long id;
    private Long albumId;
    private String fileName;
    private String fileUrl;
    private Boolean activePhoto;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
