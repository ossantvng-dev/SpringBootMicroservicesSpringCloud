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
import lombok.extern.slf4j.Slf4j;
import com.photoapp.albums.mapper.AlbumInputMapper;
import com.photoapp.commons.mapper.AlbumMapper;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumServiceImpl implements AlbumService {

    private final AlbumRepository albumRepository;
    private final AccountFeignClient accountFeignClient;
    private final PhotoFeignClient photoFeignClient;
    private final AlbumLimitsProperties albumLimitsProperties;
    private final AlbumMapper albumMapper;
    private final AlbumInputMapper albumInputMapper;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public AlbumDTO create(CreateAlbumInputDTO input) {
        log.info("Creating album for accountId={}", input.getAccountId());
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

            Album album = albumInputMapper.toEntity(normalizedInput);
            album.setAccountId(account.getId());
            album.setActiveAlbum(true);
            Album savedAlbum = albumRepository.saveAndFlush(album);

            log.info("Album created successfully albumId={} accountId={}", savedAlbum.getId(), account.getId());
            return albumMapper.toDTO(savedAlbum);
        }
        throw new ApplicationException("You can only create albums in your own accounts", HttpStatus.FORBIDDEN);
    }

    @Override
    @Transactional(readOnly = true)
    public AlbumDTO findById(Long id) {
        log.debug("Finding album by id={}", id);
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
                    log.info("Album retrieved successfully albumId={}", id);
                    return albumMapper.toDTO(existingAlbum);
                })
                .orElseThrow(() -> new ApplicationException("Album not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AlbumDTO> findAll(Map<String, String> filters) {
        log.debug("Finding all albums filters={}", filters);
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

        Page<AlbumDTO> result = albumRepository.findAll(fromFilter(albumFilterDTO), mapToPageable(filters))
                .map(album -> albumMapper.toDTO(album));
        log.info("Albums listed successfully count={}", result.getTotalElements());
        return result;
    }

    @Override
    @Transactional
    public AlbumDTO update(Long id, UpdateAlbumInputDTO input) {
        log.info("Updating album albumId={}", id);
        UpdateAlbumInputDTO normalizedInput = normalizeInputDTO(input);
        return albumRepository.findById(id)
                .map(existingAlbum -> {
                    AccountDTO accountDTO = accountFeignClient.findById(existingAlbum.getAccountId());
                    if (accountDTO == null) {
                        throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
                    }
                    if (!currentUserService.canAccessResource(String.valueOf(accountDTO.getUserId()))) {
                        throw new ApplicationException("You can only update your own albums", HttpStatus.FORBIDDEN);
                    }
                    if (!accountDTO.getActiveAccount()) {
                        throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
                    }
                    if (!existingAlbum.getActiveAlbum()) {
                        throw new ApplicationException("Album is not active", HttpStatus.FORBIDDEN);
                    }
                    existingAlbum.setTitle(normalizedInput.getTitle());
                    existingAlbum.setDescription(normalizedInput.getDescription());
                    Album updated = albumRepository.saveAndFlush(existingAlbum);
                    log.info("Album updated successfully albumId={}", id);
                    return albumMapper.toDTO(updated);
                })
                .orElseThrow(() -> new ApplicationException("Album not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public AlbumDTO activateOrDeactivate(Long id, boolean activate) {
        log.info("Updating album active state albumId={} activate={}", id, activate);
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
                    log.info("Album state updated successfully albumId={} active={}", id, activate);
                    return albumMapper.toDTO(updated);
                })
                .orElseThrow(() -> new ApplicationException("Album not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public long countByAccountId(Long accountId) {
        log.debug("Counting albums by accountId={}", accountId);
        AccountDTO accountDTO = accountFeignClient.findById(accountId);
        if (accountDTO == null) {
            throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
        }
        if (!currentUserService.canAccessResource(String.valueOf(accountDTO.getUserId()))) {
            throw new ApplicationException("You can only count albums in your own accounts", HttpStatus.FORBIDDEN);
        }
        if (!accountDTO.getActiveAccount()) {
            throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
        }
        long count = albumRepository.countByAccountId(accountId);
        log.info("Album count retrieved accountId={} count={}", accountId, count);
        return count;
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        log.warn("Deleting album by id={}", id);
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
                    log.info("Album deleted successfully albumId={}", id);
                }, () -> {
                    throw new ApplicationException("Album not found", HttpStatus.NOT_FOUND);
                });
    }

    @Override
    @Transactional
    public void deleteByAccountIds(List<Long> accountIds) {
        log.warn("Deleting albums by accountIds={}", accountIds);
        List<Long> albumIds = albumRepository.findIdsByAccountIdIn(accountIds);
        if (albumIds == null || albumIds.isEmpty()) {
            throw new ApplicationException("Albums not found for accountIds " + accountIds, HttpStatus.NOT_FOUND);
        }
        if (photoFeignClient.countByAlbumIds(albumIds) > 0) {
            throw new ApplicationException("Cannot delete albums with existing photos", HttpStatus.CONFLICT);
        }
        albumRepository.deleteByAccountIdIn(accountIds);
        log.info("Albums deleted successfully for accountIds={}", accountIds);
    }

}