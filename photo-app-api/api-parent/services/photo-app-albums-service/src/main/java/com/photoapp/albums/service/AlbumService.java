package com.photoapp.albums.service;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.albums.dto.UpdateAlbumInputDTO;
import com.photoapp.commons.dto.album.AlbumDTO;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

public interface AlbumService {

    AlbumDTO create(CreateAlbumInputDTO input);

    AlbumDTO findById(Long id);

    Page<AlbumDTO> findAll(Map<String, String> filters);

    AlbumDTO update(Long id, UpdateAlbumInputDTO input);

    AlbumDTO activateOrDeactivate(Long id, boolean activate);

    void deleteById(Long id);

    void deleteByAccountIds(List<Long> accountIds);

    long countByAccountId(Long accountId);

}

