package com.photoapp.feign.client;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.feign.configuration.FeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(
        name = "photo-app-photos-service",
        configuration = FeignConfiguration.class
)
public interface PhotoFeignClient {

    @GetMapping("/photos/{id}")
    PhotoDTO findById(@PathVariable("id") Long id);

    @GetMapping("/photos")
    Page<PhotoDTO> findAll(@RequestParam("filters") Map<String, String> filters);

    @PatchMapping("/photos/{id}/activate")
    PhotoDTO activateOrDeactivate(@PathVariable("id") Long id, @RequestParam("activate") boolean activate);

    @DeleteMapping("/photos/{id}")
    void deleteById(@PathVariable("id") Long id);

    @DeleteMapping("/photos/byAlbumIds")
    void deleteByAlbumIds(@RequestParam("albumIds") List<Long> albumIds);

    @GetMapping("/photos/countByAlbumIds")
    long countByAlbumIds(@RequestParam("albumIds") List<Long> albumIds);

}
