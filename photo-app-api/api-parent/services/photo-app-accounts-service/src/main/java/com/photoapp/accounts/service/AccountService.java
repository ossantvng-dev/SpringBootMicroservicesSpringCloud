package com.photoapp.accounts.service;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.AccountTypeDTO;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import org.springframework.data.domain.Page;

import java.util.Map;

public interface AccountService {

    AccountDTO createAccount(CreateAccountInputDTO input);

    AccountDTO changeAccountName(Long accountId, String accountName);

    AccountDTO changeAccountType(Long accountId, AccountTypeDTO accountTypeDTO);

    AccountDTO findById(Long accountId);

    Page<AccountDTO> findAll(Map<String, String> filters);

    AccountDTO activateOrDeactivate(Long accountId, boolean activate);

    void deleteById(Long accountId);

    void deleteByUserId(Long userId);

}

