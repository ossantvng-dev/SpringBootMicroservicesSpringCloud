package com.photoapp.feign.client;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.feign.configuration.FeignConfiguration;
import com.photoapp.feign.fallback.AccountFeignFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(
        name = "photo-app-accounts-service",
        configuration = FeignConfiguration.class,
        fallback = AccountFeignFallback.class
)
public interface AccountFeignClient {

    @GetMapping("/accounts/{id}")
    AccountDTO findById(@PathVariable("id") Long id);

    @GetMapping("/accounts")
    Page<AccountDTO> findAll(@RequestParam("filters") Map<String, String> filters);

    @PatchMapping("/accounts/{id}/activate")
    AccountDTO activateOrDeactivate(@PathVariable("id") Long id, @RequestParam("activate") boolean activate);

    @DeleteMapping("/accounts/{id}")
    void deleteById(@PathVariable("id") Long id);

    @DeleteMapping("/accounts/byUser/{userId}")
    void deleteByUserId(@PathVariable("userId") Long userId);

}
