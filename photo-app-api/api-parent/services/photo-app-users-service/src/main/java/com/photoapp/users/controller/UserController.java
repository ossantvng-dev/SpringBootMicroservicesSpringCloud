package com.photoapp.users.controller;

import com.photoapp.users.dto.CreateUserInputDTO;
import com.photoapp.users.dto.UpdateUserInputDTO;
import com.photoapp.users.dto.UpdateUserRolesInputDTO;
import com.photoapp.users.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<?> register(@Valid @RequestBody CreateUserInputDTO inputDTO) {
        log.info("HTTP POST /users - register request received username={}", inputDTO.getUsername());
        var result = userService.register(inputDTO);
        log.info("User registered successfully userId={}", result.getId());
        return new ResponseEntity<>(result, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> update(@PathVariable("id") Long id, @Valid @RequestBody UpdateUserInputDTO inputDTO) {
        log.info("HTTP PUT /users/{} - updateUser request received", id);
        var result = userService.update(id, inputDTO);
        log.info("User updated successfully userId={}", id);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    public ResponseEntity<?> findById(@PathVariable("id") Long id) {
        log.debug("HTTP GET /users/{} - findById request received", id);
        var result = userService.findById(id);
        log.info("User retrieved successfully userId={}", id);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> findByEmail(@PathVariable("email") String email) {
        log.debug("HTTP GET /users/email/{} - findByEmail request received", email);
        var result = userService.findByEmail(email);
        log.info("User retrieved successfully email={}", email);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @GetMapping("/username/{username}")
    public ResponseEntity<?> findByUsernameAndActiveUser(@PathVariable("username") String username) {
        log.debug("HTTP GET /users/username/{} - findByUsernameAndActiveUser request received", username);
        var result = userService.findByUsernameAndActiveUser(username, true);
        log.info("User retrieved successfully username={}", username);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @GetMapping("/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> isActive(@PathVariable("id") Long id) {
        log.debug("HTTP GET /users/{}/active - isActive request received", id);
        var result = userService.existsById(id);
        log.info("User active status retrieved userId={} active={}", id, result);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        log.debug("HTTP GET /users - findAll request received filters={}", filters);
        var result = userService.findAll(filters);
        log.info("Users listed successfully count={}", result.getTotalElements());
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> activateOrDeactivate(@PathVariable("id") Long id, @RequestParam("activate") boolean activate) {
        log.info("HTTP PATCH /users/{}/activate - activateOrDeactivate request received activate={}", id, activate);
        var result = userService.activateOrDeactivate(id, activate);
        log.info("User state updated userId={} active={}", id, activate);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PatchMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> assignOrRemoveRole(@PathVariable("id") Long id, @RequestBody @Valid UpdateUserRolesInputDTO inputDTO) {
        log.info("HTTP PATCH /users/{}/roles - assignOrRemoveRole request received", id);
        var result = userService.assignOrRemoveRole(id, inputDTO);
        log.info("User roles updated successfully userId={}", id);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteById(@PathVariable("id") Long id) {
        log.warn("HTTP DELETE /users/{} - deleteUser request received", id);
        userService.deleteById(id);
        log.info("User deleted successfully userId={}", id);
        return ResponseEntity.noContent().build();
    }

}
