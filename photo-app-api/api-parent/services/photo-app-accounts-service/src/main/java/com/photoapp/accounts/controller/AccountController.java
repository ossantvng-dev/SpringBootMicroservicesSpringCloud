package com.photoapp.accounts.controller;

import com.photoapp.accounts.service.AccountService;
import com.photoapp.commons.dto.account.AccountTypeDTO;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createAccount(@Valid @RequestBody CreateAccountInputDTO input) {
        log.info("HTTP POST /accounts - createAccount request received");
        var result = accountService.createAccount(input);
        log.info("Account created successfully accountId={}", result.getId());
        return new ResponseEntity<>(result, HttpStatus.CREATED);
    }

    @PatchMapping("/{id}/name")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> changeAccountName(@PathVariable Long id, @RequestParam String accountName) {
        log.info("HTTP PATCH /accounts/{}/name - changeAccountName request received", id);
        var result = accountService.changeAccountName(id, accountName);
        log.info("Account name updated accountId={}", id);
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{id}/type")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> changeAccountType(@PathVariable Long id, @RequestParam AccountTypeDTO accountTypeDTO) {
        log.info("HTTP PATCH /accounts/{}/type - changeAccountType request received type={}", id, accountTypeDTO);
        var result = accountService.changeAccountType(id, accountTypeDTO);
        log.info("Account type updated accountId={}", id);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        log.debug("HTTP GET /accounts/{} - findById request received", id);
        return ResponseEntity.ok(accountService.findById(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        log.debug("HTTP GET /accounts - findAll request received filters={}", filters);
        return ResponseEntity.ok(accountService.findAll(filters));
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> activateOrDeactivate(@PathVariable Long id, @RequestParam boolean activate) {
        log.info("HTTP PATCH /accounts/{}/activate activate={}", id, activate);
        return ResponseEntity.ok(accountService.activateOrDeactivate(id, activate));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> deleteAccountById(@PathVariable Long id) {
        log.warn("HTTP DELETE /accounts/{} - deleteAccount request received", id);
        accountService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/byUser/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteAccountByUserId(@PathVariable Long userId) {
        log.warn("HTTP DELETE /accounts/byUser/{} - deleteAccountByUser request received", userId);
        accountService.deleteByUserId(userId);
        return ResponseEntity.noContent().build();
    }
}