package com.photoapp.photos.service;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.photos.dto.CreatePhotoInputDTO;
import com.photoapp.photos.dto.UpdatePhotoInputDTO;
import com.photoapp.commons.dto.PagedResponseDTO;

import java.util.List;
import java.util.Map;

public interface PhotoService {

    PhotoDTO create(CreatePhotoInputDTO input);

    PhotoDTO findById(Long id);

    PagedResponseDTO<PhotoDTO> findAll(Map<String, String> filters);

    PhotoDTO update(Long id, UpdatePhotoInputDTO input);

    PhotoDTO activateOrDeactivate(Long id, boolean activate);

    void deleteById(Long id);

    void deleteByAlbumIds(List<Long> albumIds);

    long countByAlbumIdIn(List<Long> albumIds);

}

