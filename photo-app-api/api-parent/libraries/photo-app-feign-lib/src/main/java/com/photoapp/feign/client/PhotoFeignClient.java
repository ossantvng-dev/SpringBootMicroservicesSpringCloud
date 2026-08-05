package com.photoapp.feign.client;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.feign.resilience.FeignFallbacks;
import com.photoapp.feign.configuration.FeignConfiguration;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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

    String ERROR_TEMPLATE = "Failure on %s. The photo-app-photos-service is not available";

    @GetMapping("/photos/{id}")
    PhotoDTO findById(@PathVariable("id") Long id);

    @GetMapping("/photos")
    Page<PhotoDTO> findAll(@RequestParam("filters") Map<String, String> filters);

    @PatchMapping("/photos/{id}/activate")
    PhotoDTO activateOrDeactivate(@PathVariable("id") Long id, @RequestParam("activate") boolean activate);

    @DeleteMapping("/photos/{id}")
    void deleteById(@PathVariable("id") Long id);

    @DeleteMapping("/photos/byAlbumIds")
    @Retry(name = "photo-app-photos-service-deleteByAlbumIds")
    @CircuitBreaker(name = "photo-app-photos-service-deleteByAlbumIds", fallbackMethod = "deleteByAlbumIdsFallback")
    void deleteByAlbumIds(@RequestParam("albumIds") List<Long> albumIds);

    @GetMapping("/photos/countByAlbumIds")
    @Retry(name = "photo-app-photos-service-countByAlbumIds")
    @CircuitBreaker(name = "photo-app-photos-service-countByAlbumIds", fallbackMethod = "countByAlbumIdsFallback")
    long countByAlbumIds(@RequestParam("albumIds") List<Long> albumIds);

    default void deleteByAlbumIdsFallback(List<Long> albumIds, Throwable t) {
        throw FeignFallbacks.translate(t, ERROR_TEMPLATE, "deleteByAlbumIds");
    }
    default long countByAlbumIdsFallback(List<Long> albumIds, Throwable t) {
        throw FeignFallbacks.translate(t, ERROR_TEMPLATE, "countByAlbumIds");
    }

}
