package com.photoapp.albums.service.impl;

import com.photoapp.albums.configuration.AlbumLimitsProperties;
import com.photoapp.albums.dto.AlbumFilterDTO;
import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.albums.dto.UpdateAlbumInputDTO;
import com.photoapp.albums.repository.AlbumRepository;
import com.photoapp.albums.service.AlbumService;
import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.entity.Album;
import com.photoapp.feign.client.AccountFeignClient;
import com.photoapp.feign.client.PhotoFeignClient;
import com.photoapp.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.photoapp.albums.repository.specification.AlbumSpecification.fromFilter;
import static com.photoapp.commons.util.FilterBuilderUtil.mapToFilter;
import static com.photoapp.commons.util.NormalizationUtil.normalizeInputDTO;
import static com.photoapp.commons.util.PaginationUtil.mapToPageable;

@Service
@RequiredArgsConstructor
public class AlbumServiceImpl implements AlbumService {

    private final AlbumRepository albumRepository;
    private final AccountFeignClient accountFeignClient;
    private final PhotoFeignClient photoFeignClient;
    private final AlbumLimitsProperties albumLimitsProperties;
    private final ModelMapper modelMapper;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public AlbumDTO create(CreateAlbumInputDTO input) {
        CreateAlbumInputDTO normalizedInput = normalizeInputDTO(input);

        AccountDTO account = accountFeignClient.findById(normalizedInput.getAccountId());
        if (account == null || !account.getActiveAccount()) {
            throw new ApplicationException("Account not found or inactive", HttpStatus.NOT_FOUND);
        }

        if (currentUserService.canAccessResource(String.valueOf(account.getUserId()))) {
            int limit = albumLimitsProperties.getLimitForAccountType(account.getAccountTypeDTO());
            if (limit > 0) {
                long count = albumRepository.countByAccountIdAndActiveAlbumTrue(account.getId());
                if (count >= limit) {
                    throw new ApplicationException("Album limit reached for account type " +
                            account.getAccountTypeDTO(), HttpStatus.CONFLICT);
                }
            }

            Album album = modelMapper.map(normalizedInput, Album.class);
            album.setAccountId(account.getId());
            album.setActiveAlbum(true);
            Album savedAlbum = albumRepository.saveAndFlush(album);

            return modelMapper.map(savedAlbum, AlbumDTO.class);
        } else {
            throw new ApplicationException("You can only create albums in your own accounts", HttpStatus.FORBIDDEN);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AlbumDTO findById(Long id) {
        return albumRepository.findById(id)
                .map(existingAlbum -> {
                    AccountDTO accountDTO = accountFeignClient.findById(existingAlbum.getAccountId());
                    if (accountDTO == null) {
                        throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
                    }
                    if (!currentUserService.canAccessResource(String.valueOf(accountDTO.getUserId()))) {
                        throw new ApplicationException("You can only view your own albums", HttpStatus.FORBIDDEN);
                    }
                    if (!accountDTO.getActiveAccount()) {
                        throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
                    }
                    return modelMapper.map(existingAlbum, AlbumDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Album not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AlbumDTO> findAll(Map<String, String> filters) {
        AlbumFilterDTO albumFilterDTO = mapToFilter(filters, AlbumFilterDTO.class);
        boolean isAdmin = currentUserService.isAdmin();

        if (!isAdmin) {
            if (albumFilterDTO.getAccountIds() == null || albumFilterDTO.getAccountIds().isBlank()) {
                throw new ApplicationException("Non-admin users must provide accountIds filter", HttpStatus.BAD_REQUEST);
            }

            List<Long> accountIds = Arrays.stream(albumFilterDTO.getAccountIds().split(","))
                    .map(String::trim)
                    .map(Long::valueOf)
                    .toList();

            for (Long accountId : accountIds) {
                AccountDTO accountDTO = accountFeignClient.findById(accountId);
                if (accountDTO == null || !accountDTO.getActiveAccount()) {
                    throw new ApplicationException("Account not found or inactive", HttpStatus.FORBIDDEN);
                }
                if (!currentUserService.canAccessResource(String.valueOf(accountDTO.getUserId()))) {
                    throw new ApplicationException("You can only list albums from your own accounts", HttpStatus.FORBIDDEN);
                }
            }
        }

        return albumRepository.findAll(fromFilter(albumFilterDTO), mapToPageable(filters))
                .map(album -> modelMapper.map(album, AlbumDTO.class));
    }

    @Override
    @Transactional
    public AlbumDTO update(Long id, UpdateAlbumInputDTO input) {
        UpdateAlbumInputDTO normalizedInput = normalizeInputDTO(input);
        return albumRepository.findById(id)
                .map(existingAlbum -> {
                    AccountDTO accountDTO = accountFeignClient.findById(existingAlbum.getAccountId());
                    if (accountDTO == null) {
                        throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
                    }
                    if (currentUserService.canAccessResource(String.valueOf(accountDTO.getUserId()))) {
                        if (!accountDTO.getActiveAccount()) {
                            throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
                        }
                        if (!existingAlbum.getActiveAlbum()) {
                            throw new ApplicationException("Album is not active", HttpStatus.FORBIDDEN);
                        }
                        existingAlbum.setTitle(normalizedInput.getTitle());
                        existingAlbum.setDescription(normalizedInput.getDescription());
                        Album updated = albumRepository.saveAndFlush(existingAlbum);
                        return modelMapper.map(updated, AlbumDTO.class);
                    } else {
                        throw new ApplicationException("You can only update your own albums", HttpStatus.FORBIDDEN);
                    }
                })
                .orElseThrow(() -> new ApplicationException("Album not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public AlbumDTO activateOrDeactivate(Long id, boolean activate) {
        return albumRepository.findById(id)
                .map(existingAlbum -> {
                    AccountDTO accountDTO = accountFeignClient.findById(existingAlbum.getAccountId());
                    if (accountDTO == null) {
                        throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
                    }
                    if (!currentUserService.canAccessResource(String.valueOf(accountDTO.getUserId()))) {
                        throw new ApplicationException("You can only activate/deactivate your own albums", HttpStatus.FORBIDDEN);
                    }
                    if (!accountDTO.getActiveAccount()) {
                        throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
                    }
                    existingAlbum.setActiveAlbum(activate);
                    Album updated = albumRepository.saveAndFlush(existingAlbum);
                    return modelMapper.map(updated, AlbumDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Album not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public long countByAccountId(Long accountId) {
        AccountDTO accountDTO = accountFeignClient.findById(accountId);
        if (accountDTO == null) {
            throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
        }
        if (currentUserService.canAccessResource(String.valueOf(accountDTO.getUserId()))) {
            if (!accountDTO.getActiveAccount()) {
                throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
            }
            return albumRepository.countByAccountId(accountId);
        } else {
            throw new ApplicationException("You can only count albums in your own accounts", HttpStatus.FORBIDDEN);
        }
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        albumRepository.findById(id)
                .ifPresentOrElse(existingAlbum -> {
                    AccountDTO accountDTO = accountFeignClient.findById(existingAlbum.getAccountId());
                    if (accountDTO == null) {
                        throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
                    }
                    if (!currentUserService.canAccessResource(String.valueOf(accountDTO.getUserId()))) {
                        throw new ApplicationException("You can only delete your own albums", HttpStatus.FORBIDDEN);
                    }
                    if (!accountDTO.getActiveAccount()) {
                        throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
                    }
                    if (photoFeignClient.countByAlbumIds(Collections.singletonList(id)) > 0) {
                        throw new ApplicationException("Cannot delete album with existing photos", HttpStatus.CONFLICT);
                    }
                    albumRepository.delete(existingAlbum);
                }, () -> {
                    throw new ApplicationException("Album not found", HttpStatus.NOT_FOUND);
                });
    }

    @Override
    @Transactional
    public void deleteByAccountIds(List<Long> accountIds) {
        List<Long> albumIds = albumRepository.findIdsByAccountIdIn(accountIds);
        if (albumIds == null || albumIds.isEmpty()) {
            throw new ApplicationException("Albums not found for accountIds " + accountIds, HttpStatus.NOT_FOUND);
        }
        if (photoFeignClient.countByAlbumIds(albumIds) > 0) {
            throw new ApplicationException("Cannot delete albums with existing photos", HttpStatus.CONFLICT);
        }
        albumRepository.deleteByAccountIdIn(accountIds);
    }

}
