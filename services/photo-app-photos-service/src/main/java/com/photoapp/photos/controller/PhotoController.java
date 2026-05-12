package com.photoapp.photos.controller;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.photos.dto.CreatePhotoInputDTO;
import com.photoapp.photos.dto.UpdatePhotoInputDTO;
import com.photoapp.photos.service.PhotoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/photos")
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;

    @PostMapping
    public ResponseEntity<PhotoDTO> create(@Valid @RequestBody CreatePhotoInputDTO input) {
        return ResponseEntity.ok(photoService.create(input));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PhotoDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(photoService.findById(id));
    }

    @GetMapping
    public ResponseEntity<Page<PhotoDTO>> findAll(@RequestParam Map<String, String> filters) {
        return ResponseEntity.ok(photoService.findAll(filters));
    }

    @GetMapping("/countByAlbumIds")
    public ResponseEntity<?> countByAlbumIds(@RequestParam("albumIds") List<Long> albumIds) {
        return new ResponseEntity<>(photoService.countByAlbumIdIn(albumIds), HttpStatus.OK);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PhotoDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePhotoInputDTO input) {
        return ResponseEntity.ok(photoService.update(id, input));
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<PhotoDTO> activateOrDeactivate(
            @PathVariable Long id,
            @RequestParam boolean activate) {
        return ResponseEntity.ok(photoService.activateOrDeactivate(id, activate));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        photoService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/byAlbumIds")
    public ResponseEntity<Void> deleteByAlbumIds(@RequestParam List<Long> albumIds) {
        photoService.deleteByAlbumIds(albumIds);
        return ResponseEntity.noContent().build();
    }

}
