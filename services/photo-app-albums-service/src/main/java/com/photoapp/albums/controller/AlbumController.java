package com.photoapp.albums.controller;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.albums.dto.UpdateAlbumInputDTO;
import com.photoapp.albums.service.AlbumService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/albums")
@RequiredArgsConstructor
public class AlbumController {

    private final AlbumService albumService;

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateAlbumInputDTO input) {
        return new ResponseEntity<>(albumService.create(input), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        return new ResponseEntity<>(albumService.findById(id), HttpStatus.OK);
    }

    @GetMapping
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        return new ResponseEntity<>(albumService.findAll(filters), HttpStatus.OK);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody UpdateAlbumInputDTO input) {
        return new ResponseEntity<>(albumService.update(id, input), HttpStatus.OK);
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<?> activateOrDeactivate(@PathVariable Long id, @RequestParam boolean active) {
        return new ResponseEntity<>(albumService.activateOrDeactivate(id, active), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        albumService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
