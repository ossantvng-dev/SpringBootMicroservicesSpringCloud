package com.photoapp.accounts.service.impl;

import com.photoapp.accounts.dto.AccountFilterDTO;
import com.photoapp.accounts.repository.AccountRepository;
import com.photoapp.accounts.service.AccountService;
import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.AccountTypeDTO;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.entity.Account;
import com.photoapp.entity.AccountType;
import com.photoapp.feign.client.AlbumFeignClient;
import com.photoapp.feign.client.UserFeignClient;
import com.photoapp.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.photoapp.commons.mapper.AccountMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static com.photoapp.accounts.repository.specification.AccountSpecification.fromFilter;
import static com.photoapp.commons.util.FilterBuilderUtil.mapToFilter;
import static com.photoapp.commons.util.NormalizationUtil.normalizeInputDTO;
import static com.photoapp.commons.util.PaginationUtil.mapToPageable;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;
    private final AlbumFeignClient albumFeignClient;
    private final UserFeignClient userFeignClient;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public AccountDTO createAccount(CreateAccountInputDTO input) {
        log.info("Creating account for userId={}", input.getUserId());
        if (userFeignClient.isActive(input.getUserId())) {
            CreateAccountInputDTO normalized = normalizeInputDTO(input);
            Account account = accountMapper.toEntity(normalized);
            Account saved = accountRepository.saveAndFlush(account);
            log.info("Account created successfully accountId={} userId={}", saved.getId(), saved.getUserId());
            return accountMapper.toDTO(saved);
        }
        throw new ApplicationException("User is not active", HttpStatus.FORBIDDEN);
    }

    @Override
    @Transactional
    public AccountDTO changeAccountName(Long accountId, String accountName) {
        log.info("Changing account name accountId={} newName={}", accountId, accountName);
        String normalizedName = accountName != null ? accountName.trim() : null;
        return accountRepository.findById(accountId)
                .map(existingAccount -> {
                    if (!currentUserService.canAccessResource(String.valueOf(existingAccount.getUserId()))) {
                        throw new ApplicationException("You can only update your own user data", HttpStatus.FORBIDDEN);
                    }
                    if (!existingAccount.getActiveAccount()) {
                        throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
                    }
                    if (normalizedName == null || normalizedName.isBlank()) {
                        throw new ApplicationException("Account name cannot be blank", HttpStatus.BAD_REQUEST);
                    }
                    if (existingAccount.getAccountName().equalsIgnoreCase(normalizedName)) {
                        throw new ApplicationException("No changes detected", HttpStatus.BAD_REQUEST);
                    }
                    existingAccount.setAccountName(normalizedName);
                    Account updated = accountRepository.saveAndFlush(existingAccount);
                    log.info("Account name updated successfully accountId={}", accountId);
                    return accountMapper.toDTO(updated);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public AccountDTO changeAccountType(Long accountId, AccountTypeDTO accountTypeDTO) {
        log.info("Changing account type accountId={} newType={}", accountId, accountTypeDTO);
        return accountRepository.findById(accountId)
                .map(account -> {
                    if (!account.getActiveAccount()) {
                        throw new ApplicationException("Account is not active", HttpStatus.FORBIDDEN);
                    }
                    account.setAccountType(AccountType.valueOf(accountTypeDTO.name()));
                    Account updated = accountRepository.saveAndFlush(account);
                    log.info("Account type updated successfully accountId={}", accountId);
                    return accountMapper.toDTO(updated);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDTO findById(Long accountId) {
        log.debug("Finding account by id={}", accountId);
        return accountRepository.findById(accountId)
                .map(existingAccount -> {
                    if (!currentUserService.canAccessResource(String.valueOf(existingAccount.getUserId()))) {
                        throw new ApplicationException("You can only query your own user data", HttpStatus.FORBIDDEN);
                    }
                    log.info("Account found accountId={}", accountId);
                    return accountMapper.toDTO(existingAccount);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AccountDTO> findAll(Map<String, String> filters) {
        log.debug("Finding all accounts filters={}", filters);
        AccountFilterDTO accountFilterDTO = mapToFilter(filters, AccountFilterDTO.class);
        String currentUserId = currentUserService.getCurrentUserId();
        boolean isAdmin = currentUserService.isAdmin();
        if (!isAdmin) {
            if (accountFilterDTO.getUserId() != null &&
                    !accountFilterDTO.getUserId().equals(Long.valueOf(currentUserId))) {
                throw new ApplicationException("You can only list your own accounts", HttpStatus.FORBIDDEN);
            } else {
                accountFilterDTO.setUserId(Long.valueOf(currentUserId));
            }
        }
        Page<AccountDTO> result = accountRepository.findAll(fromFilter(accountFilterDTO), mapToPageable(filters))
                .map(account -> accountMapper.toDTO(account));
        log.info("Accounts listed successfully count={}", result.getTotalElements());
        return result;
    }

    @Override
    @Transactional
    public AccountDTO activateOrDeactivate(Long accountId, boolean activate) {
        log.info("Updating account active state accountId={} activate={}", accountId, activate);
        return accountRepository.findById(accountId)
                .map(existing -> {
                    if (!currentUserService.canAccessResource(String.valueOf(existing.getUserId()))) {
                        throw new ApplicationException("You can only activate/deactivate your own accounts", HttpStatus.FORBIDDEN);
                    }
                    existing.setActiveAccount(activate);
                    Account updated = accountRepository.saveAndFlush(existing);
                    log.info("Account state updated successfully accountId={} active={}", accountId, activate);
                    return accountMapper.toDTO(updated);
                })
                .orElseThrow(() -> new ApplicationException("Account not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteById(Long accountId) {
        log.warn("Deleting account by id={}", accountId);
        accountRepository.findById(accountId)
                .ifPresentOrElse(existingAccount -> {
                    if (!currentUserService.canAccessResource(String.valueOf(existingAccount.getUserId()))) {
                        throw new ApplicationException("You can only delete your own account", HttpStatus.FORBIDDEN);
                    }
                    if (albumFeignClient.countByAccountId(accountId) > 0) {
                        throw new ApplicationException("Cannot delete account with existing albums", HttpStatus.CONFLICT);
                    }
                    accountRepository.delete(existingAccount);
                    log.info("Account deleted successfully accountId={}", accountId);
                }, () -> {
                    throw new ApplicationException("Account not found", HttpStatus.NOT_FOUND);
                });
    }

    @Override
    @Transactional
    public void deleteByUserId(Long userId) {
        log.warn("Deleting accounts by userId={}", userId);
        if (accountRepository.existsByUserId(userId)) {
            accountRepository.deleteByUserId(userId);
            log.info("Accounts deleted successfully for userId={}", userId);
        } else {
            throw new ApplicationException("Account not found for user " + userId, HttpStatus.NOT_FOUND);
        }
    }
}