package com.photoapp.commons.feign;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "photo-app-accounts-service")
public interface AccountFeignClient {

    @PostMapping("/accounts")
    AccountDTO createAccount(@RequestBody CreateAccountInputDTO input);

    @GetMapping("/{id}")
    AccountDTO findById(@PathVariable Long id);

}
