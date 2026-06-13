package com.photoapp.photos.controller;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.photos.dto.CreatePhotoInputDTO;
import com.photoapp.photos.dto.UpdatePhotoInputDTO;
import com.photoapp.photos.service.PhotoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/photos")
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<PhotoDTO> create(@Valid @RequestBody CreatePhotoInputDTO input) {
        log.info("HTTP POST /photos - createPhoto request received albumId={}", input.getAlbumId());
        var result = photoService.create(input);
        log.info("Photo created successfully photoId={} albumId={}", result.getId(), input.getAlbumId());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<PhotoDTO> findById(@PathVariable Long id) {
        log.debug("HTTP GET /photos/{} - findById request received", id);
        var result = photoService.findById(id);
        log.info("Photo retrieved successfully photoId={}", id);
        return ResponseEntity.ok(result);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<Page<PhotoDTO>> findAll(@RequestParam Map<String, String> filters) {
        log.debug("HTTP GET /photos - findAll request received filters={}", filters);
        var result = photoService.findAll(filters);
        log.info("Photos listed successfully count={}", result.getTotalElements());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/countByAlbumIds")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> countByAlbumIds(@RequestParam("albumIds") List<Long> albumIds) {
        log.debug("HTTP GET /photos/countByAlbumIds albumIds={}", albumIds);
        var count = photoService.countByAlbumIdIn(albumIds);
        log.info("Photo count retrieved albumIds={} count={}", albumIds, count);
        return new ResponseEntity<>(count, HttpStatus.OK);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<PhotoDTO> update(@PathVariable Long id, @Valid @RequestBody UpdatePhotoInputDTO input) {
        log.info("HTTP PUT /photos/{} - updatePhoto request received", id);
        var result = photoService.update(id, input);
        log.info("Photo updated successfully photoId={}", id);
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<PhotoDTO> activateOrDeactivate(@PathVariable Long id, @RequestParam boolean activate) {
        log.info("HTTP PATCH /photos/{}/activate - activateOrDeactivate request received activate={}", id, activate);
        var result = photoService.activateOrDeactivate(id, activate);
        log.info("Photo state updated photoId={} active={}", id, activate);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.warn("HTTP DELETE /photos/{} - deletePhoto request received", id);
        photoService.deleteById(id);
        log.info("Photo deleted successfully photoId={}", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/byAlbumIds")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteByAlbumIds(@RequestParam List<Long> albumIds) {
        log.warn("HTTP DELETE /photos/byAlbumIds - deletePhotosByAlbumIds request received albumIds={}", albumIds);
        photoService.deleteByAlbumIds(albumIds);
        log.info("Photos deleted successfully for albumIds={}", albumIds);
        return ResponseEntity.noContent().build();
    }

}