package com.photoapp.photos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdatePhotoInputDTO {

    @NotBlank(message = "File name cannot be blank")
    @Size(max = 255, message = "File name must be at most 255 characters")
    private String fileName;

    @NotBlank(message = "File URL cannot be blank")
    @Size(max = 500, message = "File URL must be at most 500 characters")
    private String fileUrl;

}
