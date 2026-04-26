package com.photoapp.albums.service;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.albums.dto.UpdateAlbumInputDTO;
import com.photoapp.commons.dto.album.AlbumDTO;
import org.springframework.data.domain.Page;

import java.util.Map;

public interface AlbumService {

    AlbumDTO create(CreateAlbumInputDTO input);

    AlbumDTO findById(Long id);

    Page<AlbumDTO> findAll(Map<String, String> filters);

    AlbumDTO update(Long id, UpdateAlbumInputDTO input);

    AlbumDTO activateOrDeactivate(Long id, boolean active);

    void delete(Long id);

}

