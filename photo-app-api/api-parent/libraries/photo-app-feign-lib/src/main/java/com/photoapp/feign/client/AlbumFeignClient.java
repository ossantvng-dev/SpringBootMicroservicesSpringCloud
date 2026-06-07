package com.photoapp.feign.client;

import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.configuration.FeignConfiguration;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(
        name = "photo-app-albums-service",
        configuration = FeignConfiguration.class
)
public interface AlbumFeignClient {

    String ERROR_TEMPLATE = "Failure on %s. The photo-app-albums-service is not available";

    @GetMapping("/albums/{id}")
    @Retry(name = "photo-app-albums-service-findById")
    @CircuitBreaker(name = "photo-app-albums-service-findById", fallbackMethod = "findByIdFallback")
    AlbumDTO findById(@PathVariable("id") Long id);

    @GetMapping("/albums")
    @Retry(name = "photo-app-albums-service-findAll")
    @CircuitBreaker(name = "photo-app-albums-service-findAll", fallbackMethod = "findAllFallback")
    Page<AlbumDTO> findAll(@RequestParam("filters") Map<String, String> filters);

    @PatchMapping("/albums/{id}/activate")
    AlbumDTO activateOrDeactivate(@PathVariable("id") Long id, @RequestParam("activate") boolean activate);

    @DeleteMapping("/albums/{id}")
    void deleteById(@PathVariable("id") Long id);

    @DeleteMapping("/albums/byAccountIds")
    @Retry(name = "photo-app-albums-service-deleteByAccountIds")
    @CircuitBreaker(name = "photo-app-albums-service-deleteByAccountIds", fallbackMethod = "deleteByAccountIdsFallback")
    void deleteByAccountIds(@RequestParam("accountIds") List<Long> accountIds);

    @GetMapping("/albums/countByAccountId")
    @Retry(name = "photo-app-albums-service-countByAccountId")
    @CircuitBreaker(name = "photo-app-albums-service-countByAccountId", fallbackMethod = "countByAccountIdFallback")
    long countByAccountId(@RequestParam("accountId") Long accountId);

    default AlbumDTO findByIdFallback(Long id, Throwable t) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "findById"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    default Page<AlbumDTO> findAllFallback(Map<String,String> filters, Throwable ex) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "findAll"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    default void deleteByAccountIdsFallback(List<Long> accountIds, Throwable t) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "deleteByAccountIds"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    default long countByAccountIdFallback(Long accountId, Throwable t) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "countByAccountId"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }
}
