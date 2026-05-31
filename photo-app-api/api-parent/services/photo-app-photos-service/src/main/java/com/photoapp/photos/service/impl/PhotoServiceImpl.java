package com.photoapp.photos.service.impl;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.entity.Photo;
import com.photoapp.feign.client.AccountFeignClient;
import com.photoapp.feign.client.AlbumFeignClient;
import com.photoapp.photos.dto.CreatePhotoInputDTO;
import com.photoapp.photos.dto.PhotoFilterDTO;
import com.photoapp.photos.dto.UpdatePhotoInputDTO;
import com.photoapp.photos.repository.PhotoRepository;
import com.photoapp.photos.service.PhotoService;
import com.photoapp.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final AccountFeignClient accountFeignClient;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public PhotoDTO create(CreatePhotoInputDTO input) {
        CreatePhotoInputDTO normalizedInput = normalizeInputDTO(input);

        AlbumDTO album = albumFeignClient.findById(normalizedInput.getAlbumId());
        if (album == null || !album.getActiveAlbum()) {
            throw new ApplicationException("Album not found or inactive", HttpStatus.NOT_FOUND);
        }

        AccountDTO account = accountFeignClient.findById(album.getAccountId());
        if (account == null || !account.getActiveAccount()) {
            throw new ApplicationException("Account not found or inactive", HttpStatus.NOT_FOUND);
        }

        if (currentUserService.canAccessResource(String.valueOf(account.getUserId()))) {
            Photo photo = modelMapper.map(normalizedInput, Photo.class);
            photo.setAlbumId(album.getId());
            photo.setActivePhoto(true);
            Photo saved = photoRepository.saveAndFlush(photo);
            return modelMapper.map(saved, PhotoDTO.class);
        } else {
            throw new ApplicationException("You can only create photos in your own albums", HttpStatus.FORBIDDEN);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PhotoDTO findById(Long id) {
        return photoRepository.findById(id)
                .map(existingPhoto -> {
                    AlbumDTO album = albumFeignClient.findById(existingPhoto.getAlbumId());
                    if (album == null || !album.getActiveAlbum()) {
                        throw new ApplicationException("Album not found or inactive", HttpStatus.FORBIDDEN);
                    }
                    AccountDTO account = accountFeignClient.findById(album.getAccountId());
                    if (account == null || !account.getActiveAccount()) {
                        throw new ApplicationException("Account not found or inactive", HttpStatus.FORBIDDEN);
                    }
                    if (currentUserService.canAccessResource(String.valueOf(account.getUserId()))) {
                        return modelMapper.map(existingPhoto, PhotoDTO.class);
                    } else {
                        throw new ApplicationException("You can only view your own photos", HttpStatus.FORBIDDEN);
                    }
                })
                .orElseThrow(() -> new ApplicationException("Photo not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PhotoDTO> findAll(Map<String, String> filters) {
        PhotoFilterDTO photoFilterDTO = mapToFilter(filters, PhotoFilterDTO.class);
        boolean isAdmin = currentUserService.isAdmin();

        if (!isAdmin) {
            if (photoFilterDTO.getAlbumId() == null) {
                throw new ApplicationException("Non-admin users must provide albumId filter", HttpStatus.BAD_REQUEST);
            }

            AlbumDTO album = albumFeignClient.findById(photoFilterDTO.getAlbumId());
            if (album == null || !album.getActiveAlbum()) {
                throw new ApplicationException("Album not found or inactive", HttpStatus.FORBIDDEN);
            }
            AccountDTO account = accountFeignClient.findById(album.getAccountId());
            if (account == null || !account.getActiveAccount()) {
                throw new ApplicationException("Account not found or inactive", HttpStatus.FORBIDDEN);
            }
            if (!currentUserService.canAccessResource(String.valueOf(account.getUserId()))) {
                throw new ApplicationException("You can only list photos from your own albums", HttpStatus.FORBIDDEN);
            }
        }

        return photoRepository.findAll(fromFilter(photoFilterDTO), mapToPageable(filters))
                .map(photo -> modelMapper.map(photo, PhotoDTO.class));
    }

    @Override
    @Transactional
    public PhotoDTO update(Long id, UpdatePhotoInputDTO input) {
        UpdatePhotoInputDTO normalizedInput = normalizeInputDTO(input);
        return photoRepository.findById(id)
                .map(existingPhoto -> {
                    AlbumDTO album = albumFeignClient.findById(existingPhoto.getAlbumId());
                    if (album == null || !album.getActiveAlbum()) {
                        throw new ApplicationException("Album not found or inactive", HttpStatus.FORBIDDEN);
                    }
                    AccountDTO account = accountFeignClient.findById(album.getAccountId());
                    if (account == null || !account.getActiveAccount()) {
                        throw new ApplicationException("Account not found or inactive", HttpStatus.FORBIDDEN);
                    }
                    if (currentUserService.canAccessResource(String.valueOf(account.getUserId()))) {
                        if (!existingPhoto.isActivePhoto()) {
                            throw new ApplicationException("Photo is not active", HttpStatus.FORBIDDEN);
                        }
                        existingPhoto.setFileName(normalizedInput.getFileName());
                        existingPhoto.setFileUrl(normalizedInput.getFileUrl());
                        Photo updated = photoRepository.saveAndFlush(existingPhoto);
                        return modelMapper.map(updated, PhotoDTO.class);
                    } else {
                        throw new ApplicationException("You can only update your own photos", HttpStatus.FORBIDDEN);
                    }
                })
                .orElseThrow(() -> new ApplicationException("Photo not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public PhotoDTO activateOrDeactivate(Long id, boolean activate) {
        return photoRepository.findById(id)
                .map(existingPhoto -> {
                    AlbumDTO album = albumFeignClient.findById(existingPhoto.getAlbumId());
                    if (album == null || !album.getActiveAlbum()) {
                        throw new ApplicationException("Album not found or inactive", HttpStatus.FORBIDDEN);
                    }
                    AccountDTO account = accountFeignClient.findById(album.getAccountId());
                    if (account == null || !account.getActiveAccount()) {
                        throw new ApplicationException("Account not found or inactive", HttpStatus.FORBIDDEN);
                    }
                    if (currentUserService.canAccessResource(String.valueOf(account.getUserId()))) {
                        existingPhoto.setActivePhoto(activate);
                        Photo updated = photoRepository.saveAndFlush(existingPhoto);
                        return modelMapper.map(updated, PhotoDTO.class);
                    } else {
                        throw new ApplicationException("You can only activate/deactivate your own photos", HttpStatus.FORBIDDEN);
                    }
                })
                .orElseThrow(() -> new ApplicationException("Photo not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        photoRepository.findById(id)
                .ifPresentOrElse(existingPhoto -> {
                    AlbumDTO album = albumFeignClient.findById(existingPhoto.getAlbumId());
                    if (album == null || !album.getActiveAlbum()) {
                        throw new ApplicationException("Album not found or inactive", HttpStatus.FORBIDDEN);
                    }
                    AccountDTO account = accountFeignClient.findById(album.getAccountId());
                    if (account == null || !account.getActiveAccount()) {
                        throw new ApplicationException("Account not found or inactive", HttpStatus.FORBIDDEN);
                    }
                    if (currentUserService.canAccessResource(String.valueOf(account.getUserId()))) {
                        photoRepository.delete(existingPhoto);
                    } else {
                        throw new ApplicationException("You can only delete your own photos", HttpStatus.FORBIDDEN);
                    }
                }, () -> {
                    throw new ApplicationException("Photo not found", HttpStatus.NOT_FOUND);
                });
    }

    @Override
    @Transactional
    public void deleteByAlbumIds(List<Long> albumIds) {
        photoRepository.deleteByAlbumIdIn(albumIds);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByAlbumIdIn(List<Long> albumIds) {
        boolean isAdmin = currentUserService.isAdmin();
        if (!isAdmin) {
            for (Long albumId : albumIds) {
                AlbumDTO album = albumFeignClient.findById(albumId);
                if (album == null || !album.getActiveAlbum()) {
                    throw new ApplicationException("Album not found or inactive", HttpStatus.FORBIDDEN);
                }
                AccountDTO account = accountFeignClient.findById(album.getAccountId());
                if (account == null || !account.getActiveAccount()) {
                    throw new ApplicationException("Account not found or inactive", HttpStatus.FORBIDDEN);
                }
                if (!currentUserService.canAccessResource(String.valueOf(account.getUserId()))) {
                    throw new ApplicationException("You can only count photos in your own albums", HttpStatus.FORBIDDEN);
                }
            }
        }
        return photoRepository.countByAlbumIdIn(albumIds);
    }

}
