package com.photoapp.accounts.service;

import com.photoapp.accounts.dto.AccountDTO;
import com.photoapp.accounts.dto.CreateAccountInputDTO;
import org.springframework.data.domain.Page;

import java.util.Map;
import java.util.Set;

public interface AccountService {

    AccountDTO createAccount(CreateAccountInputDTO input);

    AccountDTO changeAccountName(Long accountId, String accountName);

    Page<AccountDTO> findAll(Map<String, String> filters);

    AccountDTO activateOrDeactivate(Long accountId, boolean active);

    void deleteAccountById(Long accountId);

    AccountDTO assignRoles(Long accountId, Set<Long> roleIds);

    AccountDTO removeRoles(Long accountId, Set<Long> roleIds);

}

