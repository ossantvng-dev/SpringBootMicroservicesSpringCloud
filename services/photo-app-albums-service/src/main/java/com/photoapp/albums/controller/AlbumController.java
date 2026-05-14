package com.photoapp.albums.controller;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.albums.dto.UpdateAlbumInputDTO;
import com.photoapp.albums.service.AlbumService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/albums")
@RequiredArgsConstructor
public class AlbumController {

    private final AlbumService albumService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateAlbumInputDTO input) {
        return new ResponseEntity<>(albumService.create(input), HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> findById(@PathVariable("id") Long id) {
        return new ResponseEntity<>(albumService.findById(id), HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        return new ResponseEntity<>(albumService.findAll(filters), HttpStatus.OK);
    }

    @GetMapping("/countByAccountId")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> countByAccountId(@RequestParam("accountId") Long accountId) {
        return new ResponseEntity<>(albumService.countByAccountId(accountId), HttpStatus.OK);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> update(@PathVariable("id") Long id, @Valid @RequestBody UpdateAlbumInputDTO input) {
        return new ResponseEntity<>(albumService.update(id, input), HttpStatus.OK);
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> activateOrDeactivate(
            @PathVariable("id") Long id,
            @RequestParam("activate") boolean activate) {
        return new ResponseEntity<>(albumService.activateOrDeactivate(id, activate), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> delete(@PathVariable("id") Long id) {
        albumService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/byAccountIds")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteByAccountIds(@RequestParam("accountIds") List<Long> accountIds) {
        albumService.deleteByAccountIds(accountIds);
        return ResponseEntity.noContent().build();
    }

}
