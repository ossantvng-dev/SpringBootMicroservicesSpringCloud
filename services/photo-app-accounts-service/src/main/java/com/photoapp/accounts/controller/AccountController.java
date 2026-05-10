package com.photoapp.accounts.controller;

import com.photoapp.accounts.service.AccountService;
import com.photoapp.commons.dto.account.AccountType;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public ResponseEntity<?> createAccount(@Valid @RequestBody CreateAccountInputDTO input) {
        return new ResponseEntity<>(accountService.createAccount(input), HttpStatus.CREATED);
    }

    @PatchMapping(
            value = "/{id}/name",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> changeAccountName(@PathVariable Long id, @RequestParam String accountName) {
        return new ResponseEntity<>(accountService.changeAccountName(id, accountName), HttpStatus.OK);
    }

    @PatchMapping(
            value = "/{id}/type",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> changeAccountType(@PathVariable Long id, @RequestParam AccountType accountType) {
        return new ResponseEntity<>(accountService.changeAccountType(id, accountType), HttpStatus.OK);
    }

    @GetMapping(
            value = "/{id}",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> findById(@PathVariable Long id) {
        return new ResponseEntity<>(accountService.findById(id), HttpStatus.OK);
    }

    @GetMapping(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        return new ResponseEntity<>(accountService.findAll(filters), HttpStatus.OK);
    }

    @PatchMapping(
            value = "/{id}/activate",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}
    )
    public ResponseEntity<?> activateOrDeactivate(@PathVariable Long id, @RequestParam boolean activate) {
        return new ResponseEntity<>(accountService.activateOrDeactivate(id, activate), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAccountById(@PathVariable Long id) {
        accountService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/byUser/{userId}")
    public ResponseEntity<?> deleteAccountByUserId(@PathVariable Long userId) {
        accountService.deleteByUserId(userId);
        return ResponseEntity.noContent().build();
    }

}
