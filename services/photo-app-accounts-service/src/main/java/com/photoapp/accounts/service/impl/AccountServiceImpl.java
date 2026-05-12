package com.photoapp.accounts.service.impl;

import com.photoapp.accounts.dto.AccountFilterDTO;
import com.photoapp.accounts.entity.Account;
import com.photoapp.accounts.repository.AccountRepository;
import com.photoapp.accounts.service.AccountService;
import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.AccountType;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.AlbumFeignClient;
import com.photoapp.feign.UserFeignClient;
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
    private final AlbumFeignClient albumFeignClient;
    private final UserFeignClient userFeignClient;

    @Override
    @Transactional
    public AccountDTO createAccount(CreateAccountInputDTO input) {
        if (userFeignClient.isActive(input.getUserId())) {
            CreateAccountInputDTO normalized = normalizeInputDTO(input);
            Account account = modelMapper.map(normalized, Account.class);
            return modelMapper.map(accountRepository.saveAndFlush(account), AccountDTO.class);
        } else {
            throw new ApplicationException("User is not active", HttpStatus.FORBIDDEN);
        }
    }

    @Override
    @Transactional
    public AccountDTO changeAccountName(Long accountId, String accountName) {
        String normalizedName = accountName != null ? accountName.trim() : null;
        return accountRepository.findById(accountId)
                .map(existingAccount -> {
                    if (existingAccount.getActiveAccount()) {
                        if (normalizedName == null || normalizedName.isBlank()) {
                            throw new ApplicationException("Account name cannot be blank", HttpStatus.BAD_REQUEST);
                        }
                        if (existingAccount.getAccountName().equalsIgnoreCase(normalizedName)) {
                            throw new ApplicationException("No changes detected", HttpStatus.BAD_REQUEST);
                        }
                        existingAccount.setAccountName(normalizedName);
                        Account updated = accountRepository.saveAndFlush(existingAccount);
                        return modelMapper.map(updated, AccountDTO.class);
                    } else {
                        throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
                    }
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public AccountDTO changeAccountType(Long accountId, AccountType accountType) {
        return accountRepository.findById(accountId)
                .map(account -> {
                    if (account.getActiveAccount()) {
                        account.setAccountType(accountType);
                        return modelMapper.map(accountRepository.saveAndFlush(account), AccountDTO.class);
                    } else {
                        throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
                    }
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDTO findById(Long accountId) {
        return accountRepository.findById(accountId)
                .map(account -> modelMapper.map(account, AccountDTO.class))
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
    public AccountDTO activateOrDeactivate(Long accountId, boolean activate) {
        return accountRepository.findById(accountId)
                .map(existing -> {
                    existing.setActiveAccount(activate);
                    return modelMapper.map(accountRepository.saveAndFlush(existing), AccountDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteById(Long accountId) {
        if (accountRepository.existsById(accountId)) {
            if (albumFeignClient.countByAccountId(accountId) > 0) {
                throw new ApplicationException("Cannot delete account with existing albums", HttpStatus.CONFLICT);
            }
            accountRepository.deleteById(accountId);
        } else {
            throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public void deleteByUserId(Long userId) {
        if (accountRepository.existsByUserId(userId)) {
            accountRepository.deleteByUserId(userId);
        } else {
            throw new ApplicationException("Account not found for user " + userId, HttpStatus.NOT_FOUND);
        }
    }

}
