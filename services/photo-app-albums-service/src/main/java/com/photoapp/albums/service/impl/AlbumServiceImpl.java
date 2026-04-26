package com.photoapp.albums.service.impl;

import com.photoapp.albums.configuration.AlbumLimitsProperties;
import com.photoapp.albums.dto.AlbumFilterDTO;
import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.albums.dto.UpdateAlbumInputDTO;
import com.photoapp.albums.entity.Album;
import com.photoapp.albums.repository.AlbumRepository;
import com.photoapp.albums.service.AlbumService;
import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.commons.feign.AccountFeignClient;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final AlbumLimitsProperties albumLimitsProperties;
    private final ModelMapper modelMapper;

    @Override
    @Transactional
    public AlbumDTO create(CreateAlbumInputDTO input) {
        CreateAlbumInputDTO normalizedInput = normalizeInputDTO(input);

        AccountDTO account = accountFeignClient.findById(normalizedInput.getAccountId());
        if (account == null || !account.getActiveAccount()) {
            throw new ApplicationException("Account not found or inactive", HttpStatus.NOT_FOUND);
        }

        int limit = albumLimitsProperties.getLimitForAccountType(account.getAccountType());
        if (limit > 0) {
            long count = albumRepository.countByAccountIdAndActiveAlbumTrue(account.getId());
            if (count >= limit) {
                throw new ApplicationException("Album limit reached for account type " + account.getAccountType(),
                        HttpStatus.CONFLICT);
            }
        }

        Album album = modelMapper.map(normalizedInput, Album.class);
        album.setAccountId(account.getId());
        album.setActiveAlbum(true);

        return modelMapper.map(albumRepository.save(album), AlbumDTO.class);
    }

    @Override
    @Transactional(readOnly = true)
    public AlbumDTO findById(Long id) {
        return albumRepository.findById(id)
                .map(album -> modelMapper.map(album, AlbumDTO.class))
                .orElseThrow(() -> new ApplicationException("Album not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AlbumDTO> findAll(Map<String, String> filters) {
        return albumRepository.findAll(
                fromFilter(mapToFilter(filters, AlbumFilterDTO.class)),
                mapToPageable(filters)
        ).map(album -> modelMapper.map(album, AlbumDTO.class));
    }

    @Override
    @Transactional
    public AlbumDTO update(Long id, UpdateAlbumInputDTO input) {
        UpdateAlbumInputDTO normalizedInput = normalizeInputDTO(input);

        return albumRepository.findById(id)
                .map(existing -> {
                    existing.setTitle(normalizedInput.getTitle());
                    existing.setDescription(normalizedInput.getDescription());
                    Album updated = albumRepository.save(existing);
                    return modelMapper.map(updated, AlbumDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Album not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public AlbumDTO activateOrDeactivate(Long id, boolean active) {
        return albumRepository.findById(id)
                .map(existing -> {
                    existing.setActiveAlbum(active);
                    Album updated = albumRepository.save(existing);
                    return modelMapper.map(updated, AlbumDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Album not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (albumRepository.existsById(id)) {
            albumRepository.deleteById(id);
        } else {
            throw new ApplicationException("Album not found", HttpStatus.NOT_FOUND);
        }
    }
}
