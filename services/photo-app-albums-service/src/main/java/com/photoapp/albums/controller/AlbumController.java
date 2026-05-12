package com.photoapp.albums.controller;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.albums.dto.UpdateAlbumInputDTO;
import com.photoapp.albums.service.AlbumService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/albums")
@RequiredArgsConstructor
public class AlbumController {

    private final AlbumService albumService;

    @PostMapping(
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> create(@Valid @RequestBody CreateAlbumInputDTO input) {
        return new ResponseEntity<>(albumService.create(input), HttpStatus.CREATED);
    }

    @GetMapping(
            value = "/{id}",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> findById(@PathVariable("id") Long id) {
        return new ResponseEntity<>(albumService.findById(id), HttpStatus.OK);
    }

    @GetMapping(
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        return new ResponseEntity<>(albumService.findAll(filters), HttpStatus.OK);
    }

    @GetMapping(
            value = "/countByAccountId",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> countByAccountId(@RequestParam("accountId") Long accountId) {
        return new ResponseEntity<>(albumService.countByAccountId(accountId), HttpStatus.OK);
    }

    @PutMapping(
            value = "/{id}",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> update(@PathVariable("id") Long id, @Valid @RequestBody UpdateAlbumInputDTO input) {
        return new ResponseEntity<>(albumService.update(id, input), HttpStatus.OK);
    }

    @PatchMapping(
            value = "/{id}/activate",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> activateOrDeactivate(
            @PathVariable("id") Long id,
            @RequestParam("activate") boolean activate) {
        return new ResponseEntity<>(albumService.activateOrDeactivate(id, activate), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") Long id) {
        albumService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/byAccountIds")
    public ResponseEntity<Void> deleteByAccountIds(@RequestParam("accountIds") List<Long> accountIds) {
        albumService.deleteByAccountIds(accountIds);
        return ResponseEntity.noContent().build();
    }

}
