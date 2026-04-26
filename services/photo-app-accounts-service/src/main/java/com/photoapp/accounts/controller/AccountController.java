package com.photoapp.accounts.controller;

import com.photoapp.accounts.service.AccountService;
import com.photoapp.commons.dto.account.AccountType;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<?> createAccount(@Valid @RequestBody CreateAccountInputDTO input) {
        return new ResponseEntity<>(accountService.createAccount(input), HttpStatus.CREATED);
    }

    @PatchMapping("/{id}/name")
    public ResponseEntity<?> changeAccountName(@PathVariable Long id, @RequestParam String accountName) {
        return new ResponseEntity<>(accountService.changeAccountName(id, accountName), HttpStatus.OK);
    }

    @PatchMapping("/{id}/type")
    public ResponseEntity<?> changeAccountType(@PathVariable Long id, @RequestParam AccountType accountType) {
        return new ResponseEntity<>(accountService.changeAccountType(id, accountType), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        return new ResponseEntity<>(accountService.findById(id), HttpStatus.OK);
    }

    @GetMapping
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        return new ResponseEntity<>(accountService.findAll(filters), HttpStatus.OK);
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<?> activateOrDeactivate(@PathVariable Long id, @RequestParam boolean active) {
        return new ResponseEntity<>(accountService.activateOrDeactivate(id, active), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAccountById(@PathVariable Long id) {
        accountService.deleteAccountById(id);
        return ResponseEntity.noContent().build();
    }

}
