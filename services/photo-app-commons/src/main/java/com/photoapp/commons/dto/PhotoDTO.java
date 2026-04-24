package com.photoapp.commons.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhotoDTO {

    private Long id;
    private String fileName;
    private String fileUrl;
    private Boolean activePhoto;

}

