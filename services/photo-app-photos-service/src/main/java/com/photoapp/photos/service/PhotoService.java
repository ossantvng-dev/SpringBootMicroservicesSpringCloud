package com.photoapp.photos.service;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.photos.dto.CreatePhotoInputDTO;
import com.photoapp.photos.dto.UpdatePhotoInputDTO;
import org.springframework.data.domain.Page;

import java.util.Map;

public interface PhotoService {

    PhotoDTO create(CreatePhotoInputDTO input);

    PhotoDTO findById(Long id);

    Page<PhotoDTO> findAll(Map<String, String> filters);

    PhotoDTO update(Long id, UpdatePhotoInputDTO input);

    PhotoDTO activateOrDeactivate(Long id, boolean active);

    void deleteById(Long id);

}

