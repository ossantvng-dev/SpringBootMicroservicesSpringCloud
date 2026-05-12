package com.photoapp.feign;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "photo-app-accounts-service")
public interface AccountFeignClient {

    @PostMapping("/accounts")
    AccountDTO createAccount(@RequestBody CreateAccountInputDTO input);

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
