package com.photoapp.users.controller;

import com.photoapp.users.dto.CreateUserInputDTO;
import com.photoapp.users.dto.UpdateUserInputDTO;
import com.photoapp.users.dto.UpdateUserRolesInputDTO;
import com.photoapp.users.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<?> register(@Valid @RequestBody CreateUserInputDTO inputDTO) {
        return new ResponseEntity<>(userService.register(inputDTO), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody UpdateUserInputDTO inputDTO) {
        return new ResponseEntity<>(userService.update(id, inputDTO), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        return new ResponseEntity<>(userService.findById(id), HttpStatus.OK);
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<?> findByEmail(@PathVariable String email) {
        return new ResponseEntity<>(userService.findByEmail(email), HttpStatus.OK);
    }

    @GetMapping("/username/{username}")
    public ResponseEntity<?> findByUsername(@PathVariable String username) {
        return new ResponseEntity<>(userService.findByUsername(username), HttpStatus.OK);
    }

    @GetMapping
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        return new ResponseEntity<>(userService.findAll(filters), HttpStatus.OK);
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<?> activateOrDeactivate(@PathVariable Long id, @RequestParam boolean active) {
        return new ResponseEntity<>(userService.activateOrDeactivate(id, active), HttpStatus.OK);
    }

    @PatchMapping("/{id}/roles")
    public ResponseEntity<?> assignOrRemoveRole(
            @PathVariable Long id, @RequestBody @Valid UpdateUserRolesInputDTO inputDTO) {
        return new ResponseEntity<>(userService.assignOrRemoveRole(id, inputDTO), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteById(@PathVariable Long id) {
        userService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
