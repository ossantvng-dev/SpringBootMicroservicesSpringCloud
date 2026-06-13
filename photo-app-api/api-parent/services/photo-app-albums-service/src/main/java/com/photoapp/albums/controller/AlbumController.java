package com.photoapp.albums.controller;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.albums.dto.UpdateAlbumInputDTO;
import com.photoapp.albums.service.AlbumService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/albums")
@RequiredArgsConstructor
public class AlbumController {

    private final AlbumService albumService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateAlbumInputDTO input) {
        log.info("HTTP POST /albums - createAlbum request received accountId={}", input.getAccountId());
        var result = albumService.create(input);
        log.info("Album created successfully albumId={} accountId={}", result.getId(), input.getAccountId());
        return new ResponseEntity<>(result, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> findById(@PathVariable("id") Long id) {
        log.debug("HTTP GET /albums/{} - findById request received", id);
        var result = albumService.findById(id);
        log.info("Album retrieved successfully albumId={}", id);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        log.debug("HTTP GET /albums - findAll request received filters={}", filters);
        var result = albumService.findAll(filters);
        log.info("Albums listed successfully count={}", result.getTotalElements());
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @GetMapping("/countByAccountId")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> countByAccountId(@RequestParam("accountId") Long accountId) {
        log.debug("HTTP GET /albums/countByAccountId accountId={}", accountId);
        var count = albumService.countByAccountId(accountId);
        log.info("Album count retrieved accountId={} count={}", accountId, count);
        return new ResponseEntity<>(count, HttpStatus.OK);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> update(@PathVariable("id") Long id, @Valid @RequestBody UpdateAlbumInputDTO input) {
        log.info("HTTP PUT /albums/{} - updateAlbum request received", id);
        var result = albumService.update(id, input);
        log.info("Album updated successfully albumId={}", id);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> activateOrDeactivate(@PathVariable("id") Long id, @RequestParam("activate") boolean activate) {
        log.info("HTTP PATCH /albums/{}/activate - activateOrDeactivate request received activate={}", id, activate);
        var result = albumService.activateOrDeactivate(id, activate);
        log.info("Album state updated albumId={} active={}", id, activate);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> delete(@PathVariable("id") Long id) {
        log.warn("HTTP DELETE /albums/{} - deleteAlbum request received", id);
        albumService.deleteById(id);
        log.info("Album deleted successfully albumId={}", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/byAccountIds")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteByAccountIds(@RequestParam("accountIds") List<Long> accountIds) {
        log.warn("HTTP DELETE /albums/byAccountIds - deleteAlbumsByAccountIds request received accountIds={}", accountIds);
        albumService.deleteByAccountIds(accountIds);
        log.info("Albums deleted successfully for accountIds={}", accountIds);
        return ResponseEntity.noContent().build();
    }
}
