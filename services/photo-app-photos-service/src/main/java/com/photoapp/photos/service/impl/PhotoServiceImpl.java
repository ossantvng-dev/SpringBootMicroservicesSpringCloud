package com.photoapp.photos.service.impl;

import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.commons.feign.AlbumFeignClient;
import com.photoapp.photos.dto.CreatePhotoInputDTO;
import com.photoapp.photos.dto.PhotoFilterDTO;
import com.photoapp.photos.dto.UpdatePhotoInputDTO;
import com.photoapp.photos.entity.Photo;
import com.photoapp.photos.repository.PhotoRepository;
import com.photoapp.photos.service.PhotoService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static com.photoapp.commons.util.FilterBuilderUtil.mapToFilter;
import static com.photoapp.commons.util.NormalizationUtil.normalizeInputDTO;
import static com.photoapp.commons.util.PaginationUtil.mapToPageable;
import static com.photoapp.photos.repository.specification.PhotoSpecification.fromFilter;

@Service
@RequiredArgsConstructor
public class PhotoServiceImpl implements PhotoService {

    private final PhotoRepository photoRepository;
    private final ModelMapper modelMapper;
    private final AlbumFeignClient albumFeignClient;

    @Override
    @Transactional
    public PhotoDTO create(CreatePhotoInputDTO input) {
        CreatePhotoInputDTO normalizedInput = normalizeInputDTO(input);

        AlbumDTO album = albumFeignClient.findById(normalizedInput.getAlbumId());
        if (album == null || !album.getActiveAlbum()) {
            throw new ApplicationException("Album not found or inactive", HttpStatus.NOT_FOUND);
        }

        Photo photo = modelMapper.map(normalizedInput, Photo.class);
        return modelMapper.map(photoRepository.save(photo), PhotoDTO.class);
    }

    @Override
    @Transactional(readOnly = true)
    public PhotoDTO findById(Long id) {
        return photoRepository.findById(id)
                .map(photo -> modelMapper.map(photo, PhotoDTO.class))
                .orElseThrow(() -> new ApplicationException("Photo not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PhotoDTO> findAll(Map<String, String> filters) {
        return photoRepository.findAll(
                fromFilter(mapToFilter(filters, PhotoFilterDTO.class)),
                mapToPageable(filters)
        ).map(photo -> modelMapper.map(photo, PhotoDTO.class));
    }

    @Override
    @Transactional
    public PhotoDTO update(Long id, UpdatePhotoInputDTO input) {
        UpdatePhotoInputDTO normalizedInput = normalizeInputDTO(input);

        return photoRepository.findById(id)
                .map(existing -> {
                    existing.setFileName(normalizedInput.getFileName());
                    existing.setFileUrl(normalizedInput.getFileUrl());
                    Photo updated = photoRepository.save(existing);
                    return modelMapper.map(updated, PhotoDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Photo not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public PhotoDTO activateOrDeactivate(Long id, boolean active) {
        return photoRepository.findById(id)
                .map(existing -> {
                    existing.setActivePhoto(active);
                    Photo updated = photoRepository.save(existing);
                    return modelMapper.map(updated, PhotoDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Photo not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (photoRepository.existsById(id)) {
            photoRepository.deleteById(id);
        } else {
            throw new ApplicationException("Photo not found", HttpStatus.NOT_FOUND);
        }
    }
}
