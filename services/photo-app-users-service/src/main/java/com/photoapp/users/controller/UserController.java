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
    public ResponseEntity<?> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateUserInputDTO inputDTO) {
        return new ResponseEntity<>(userService.update(id, inputDTO), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable("id") Long id) {
        return new ResponseEntity<>(userService.findById(id), HttpStatus.OK);
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<?> findByEmail(@PathVariable("email") String email) {
        return new ResponseEntity<>(userService.findByEmail(email), HttpStatus.OK);
    }

    @GetMapping("/username/{username}")
    public ResponseEntity<?> findByUsernameAndActiveUser(@PathVariable("username") String username) {
        return new ResponseEntity<>(userService.findByUsernameAndActiveUser(username, true), HttpStatus.OK);
    }

    @GetMapping("/{id}/active")
    public ResponseEntity<?> isActive(@PathVariable("id") Long id) {
        return new ResponseEntity<>(userService.existsById(id), HttpStatus.OK);
    }

    @GetMapping
    public ResponseEntity<?> findAll(@RequestParam Map<String, String> filters) {
        return new ResponseEntity<>(userService.findAll(filters), HttpStatus.OK);
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<?> activateOrDeactivate(
            @PathVariable("id") Long id,
            @RequestParam("activate") boolean activate) {
        return new ResponseEntity<>(userService.activateOrDeactivate(id, activate), HttpStatus.OK);
    }

    @PatchMapping("/{id}/roles")
    public ResponseEntity<?> assignOrRemoveRole(
            @PathVariable("id") Long id,
            @RequestBody @Valid UpdateUserRolesInputDTO inputDTO) {
        return new ResponseEntity<>(userService.assignOrRemoveRole(id, inputDTO), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteById(@PathVariable("id") Long id) {
        userService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

}
