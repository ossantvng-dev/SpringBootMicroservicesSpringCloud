package com.photoapp.accounts.service.impl;

import com.photoapp.accounts.dto.AccountFilterDTO;
import com.photoapp.accounts.entity.Account;
import com.photoapp.accounts.repository.AccountRepository;
import com.photoapp.accounts.service.AccountService;
import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.AccountType;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import com.photoapp.commons.exception.ApplicationException;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static com.photoapp.accounts.repository.specification.AccountSpecification.fromFilter;
import static com.photoapp.commons.util.FilterBuilderUtil.mapToFilter;
import static com.photoapp.commons.util.NormalizationUtil.normalizeInputDTO;
import static com.photoapp.commons.util.PaginationUtil.mapToPageable;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final ModelMapper modelMapper;

    @Override
    @Transactional
    public AccountDTO createAccount(CreateAccountInputDTO input) {
        CreateAccountInputDTO normalized = normalizeInputDTO(input);
        Account account = modelMapper.map(normalized, Account.class);
        return modelMapper.map(accountRepository.save(account), AccountDTO.class);
    }

    @Override
    @Transactional
    public AccountDTO changeAccountName(Long accountId, String accountName) {
        return accountRepository.findById(accountId)
                .map(existingAccount -> {
                    String normalizedName = accountName != null ? accountName.trim() : null;

                    if (normalizedName == null || normalizedName.isBlank()) {
                        throw new ApplicationException("Account name cannot be blank", HttpStatus.BAD_REQUEST);
                    }

                    if (existingAccount.getAccountName().equalsIgnoreCase(normalizedName)) {
                        throw new ApplicationException("No changes detected", HttpStatus.BAD_REQUEST);
                    }

                    existingAccount.setAccountName(normalizedName);
                    Account updated = accountRepository.save(existingAccount);

                    return modelMapper.map(updated, AccountDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public AccountDTO changeAccountType(Long accountId, AccountType accountType) {
        return accountRepository.findById(accountId)
                .map(account -> {
                    account.setAccountType(accountType);
                    return modelMapper.map(accountRepository.save(account), AccountDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AccountDTO> findAll(Map<String, String> filters) {
        return accountRepository.findAll(
                fromFilter(mapToFilter(filters, AccountFilterDTO.class)),
                mapToPageable(filters)
        ).map(account -> modelMapper.map(account, AccountDTO.class));
    }

    @Override
    @Transactional
    public AccountDTO activateOrDeactivate(Long accountId, boolean active) {
        return accountRepository.findById(accountId)
                .map(existing -> {
                    existing.setActiveAccount(active);
                    return modelMapper.map(accountRepository.save(existing), AccountDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteAccountById(Long accountId) {
        if (accountRepository.existsById(accountId)) {
            accountRepository.deleteById(accountId);
        } else {
            throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
        }
    }

}
