package com.photoapp.feign.fallback;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.exception.ServiceUnavailableException;
import com.photoapp.feign.client.AccountFeignClient;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.Map;

/* Feign Fallback solo entra cuando hay timeout, conexión rechazada o circuito abierto. */
@Component
public class AccountFeignFallback implements AccountFeignClient {

    @Override
    public AccountDTO findById(Long id) {
        throw new ServiceUnavailableException("photo-app-accounts-service", "findById");
    }

    @Override
    public Page<AccountDTO> findAll(Map<String, String> filters) {
        throw new ServiceUnavailableException("photo-app-accounts-service", "findAll");
    }

    @Override
    public AccountDTO activateOrDeactivate(Long id, boolean activate) {
        throw new ServiceUnavailableException("photo-app-accounts-service", "activateOrDeactivate");
    }

    @Override
    public void deleteById(Long id) {
        throw new ServiceUnavailableException("photo-app-accounts-service", "deleteById");
    }

    @Override
    public void deleteByUserId(Long userId) {
        throw new ServiceUnavailableException("photo-app-accounts-service", "deleteByUserId");
    }

}
