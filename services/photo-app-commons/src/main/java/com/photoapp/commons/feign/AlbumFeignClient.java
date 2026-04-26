package com.photoapp.commons.feign;

import com.photoapp.commons.dto.album.AlbumDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "photo-app-albums-service")
public interface AlbumFeignClient {

    @GetMapping("/albums/{id}")
    AlbumDTO findById(@PathVariable("id") Long id);

}
