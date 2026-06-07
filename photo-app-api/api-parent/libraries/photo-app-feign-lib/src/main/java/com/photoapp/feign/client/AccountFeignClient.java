package com.photoapp.feign.client;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.configuration.FeignConfiguration;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(
        name = "photo-app-accounts-service",
        configuration = FeignConfiguration.class
)
public interface AccountFeignClient {

    String ERROR_TEMPLATE = "Failure on %s. The photo-app-accounts-service is not available";

    @GetMapping("/accounts/{id}")
    @Retry(name = "photo-app-accounts-service-findById")
    @CircuitBreaker(name = "photo-app-accounts-service-findById", fallbackMethod = "findByIdFallback")
    AccountDTO findById(@PathVariable("id") Long id);

    @GetMapping("/accounts")
    @Retry(name = "photo-app-accounts-service-findAll")
    @CircuitBreaker(name = "photo-app-accounts-service-findAll", fallbackMethod = "findAllFallback")
    Page<AccountDTO> findAll(@RequestParam("filters") Map<String, String> filters);

    @PatchMapping("/accounts/{id}/activate")
    AccountDTO activateOrDeactivate(@PathVariable("id") Long id, @RequestParam("activate") boolean activate);

    @DeleteMapping("/accounts/{id}")
    void deleteById(@PathVariable("id") Long id);

    @DeleteMapping("/accounts/byUser/{userId}")
    @CircuitBreaker(name = "photo-app-accounts-service-deleteByUserId", fallbackMethod = "deleteByUserIdFallback")
    @Retry(name = "photo-app-accounts-service-deleteByUserId")
    void deleteByUserId(@PathVariable("userId") Long userId);

    default Page<AccountDTO> findAllFallback(Map<String,String> filters, Throwable ex) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "findAll"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    default void deleteByUserIdFallback(Long userId, Throwable t) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "deleteByUserId"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    default AccountDTO findByIdFallback(Long id, Throwable t) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "findById"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

}
