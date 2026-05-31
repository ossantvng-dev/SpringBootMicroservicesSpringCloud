package com.photoapp.feign.client;

import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.feign.configuration.FeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(
        name = "photo-app-albums-service",
        configuration = FeignConfiguration.class
)
public interface AlbumFeignClient {

    @GetMapping("/albums/{id}")
    AlbumDTO findById(@PathVariable("id") Long id);

    @GetMapping("/albums")
    Page<AlbumDTO> findAll(@RequestParam("filters") Map<String, String> filters);

    @PatchMapping("/albums/{id}/activate")
    AlbumDTO activateOrDeactivate(@PathVariable("id") Long id, @RequestParam("activate") boolean activate);

    @DeleteMapping("/albums/{id}")
    void deleteById(@PathVariable("id") Long id);

    @DeleteMapping("/albums/byAccountIds")
    void deleteByAccountIds(@RequestParam("accountIds") List<Long> accountIds);

    @GetMapping("/albums/countByAccountId")
    long countByAccountId(@RequestParam("accountId") Long accountId);

}
