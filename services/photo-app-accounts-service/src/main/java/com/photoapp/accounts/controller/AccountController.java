package com.photoapp.accounts.controller;

import com.photoapp.accounts.dto.CreateAccountInputDTO;
import com.photoapp.accounts.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

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

    @PostMapping("/{id}/roles")
    public ResponseEntity<?> assignRoles(@PathVariable Long id, @RequestBody Set<Long> roleIds) {
        return new ResponseEntity<>(accountService.assignRoles(id, roleIds), HttpStatus.OK);
    }

    @DeleteMapping("/{id}/roles")
    public ResponseEntity<?> removeRoles(@PathVariable Long id, @RequestBody Set<Long> roleIds) {
        return new ResponseEntity<>(accountService.removeRoles(id, roleIds), HttpStatus.OK);
    }

}
