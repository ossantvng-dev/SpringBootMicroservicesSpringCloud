package com.photoapp.accounts.service.impl;

import com.photoapp.accounts.dto.AccountDTO;
import com.photoapp.accounts.dto.AccountFilterDTO;
import com.photoapp.accounts.dto.CreateAccountInputDTO;
import com.photoapp.accounts.entity.Account;
import com.photoapp.accounts.entity.AccountRole;
import com.photoapp.accounts.entity.AccountRoleId;
import com.photoapp.accounts.repository.AccountRepository;
import com.photoapp.accounts.repository.AccountRoleRepository;
import com.photoapp.accounts.repository.RoleRepository;
import com.photoapp.accounts.service.AccountService;
import com.photoapp.commons.exception.ApplicationException;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

import static com.photoapp.commons.util.FilterBuilderUtil.mapToFilter;
import static com.photoapp.commons.util.NormalizationUtil.normalizeInputDTO;
import static com.photoapp.commons.util.PaginationUtil.mapToPageable;
import static com.photoapp.accounts.repository.specification.AccountSpecification.fromFilter;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final AccountRoleRepository accountRoleRepository;
    private final RoleRepository roleRepository;
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

    @Override
    @Transactional
    public AccountDTO assignRoles(Long accountId, Set<Long> roleIds) {
        return accountRepository.findById(accountId)
                .map(account -> {
                    roleIds.forEach(roleId -> {
                        roleRepository.findById(roleId)
                                .orElseThrow(() -> new ApplicationException("Role not found", HttpStatus.NOT_FOUND));
                        AccountRoleId id = new AccountRoleId(account.getId(), roleId);
                        if (!accountRoleRepository.existsById(id)) {
                            accountRoleRepository.save(new AccountRole(id));
                        }
                    });
                    return modelMapper.map(account, AccountDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public AccountDTO removeRoles(Long accountId, Set<Long> roleIds) {
        return accountRepository.findById(accountId)
                .map(account -> {
                    roleIds.forEach(roleId -> {
                        AccountRoleId id = new AccountRoleId(account.getId(), roleId);
                        if (accountRoleRepository.existsById(id)) {
                            accountRoleRepository.deleteById(id);
                        }
                    });
                    return modelMapper.map(account, AccountDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }
}
