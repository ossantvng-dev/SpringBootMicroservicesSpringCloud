package com.photoapp.accounts.controller;

import com.photoapp.accounts.service.AccountService;
import com.photoapp.commons.dto.account.AccountType;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createAccount(@Valid @RequestBody CreateAccountInputDTO input) {
        return new ResponseEntity<>(accountService.createAccount(input), HttpStatus.CREATED);
    }

    @PatchMapping("/{id}/name")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> changeAccountName(@PathVariable Long id, @RequestParam String accountName) {
        return new ResponseEntity<>(accountService.changeAccountName(id, accountName), HttpStatus.OK);
    }

    @PatchMapping("/{id}/type")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> changeAccountType(@PathVariable Long id, @RequestParam AccountType accountType) {
        return new ResponseEntity<>(accountService.changeAccountType(id, accountType), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        return new ResponseEntity<>(accountService.findById(id), HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        return new ResponseEntity<>(accountService.findAll(filters), HttpStatus.OK);
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> activateOrDeactivate(@PathVariable Long id, @RequestParam boolean activate) {
        return new ResponseEntity<>(accountService.activateOrDeactivate(id, activate), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteAccountById(@PathVariable Long id) {
        accountService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/byUser/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteAccountByUserId(@PathVariable Long userId) {
        accountService.deleteByUserId(userId);
        return ResponseEntity.noContent().build();
    }

}
