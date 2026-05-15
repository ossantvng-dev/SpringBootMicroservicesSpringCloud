package com.photoapp.auth.controller;

import com.photoapp.auth.dto.AuthorizationResponseDTO;
import com.photoapp.auth.dto.LoginRequestDTO;
import com.photoapp.auth.dto.RefreshTokenRequestDTO;
import com.photoapp.auth.service.AuthorizationService;
import com.photoapp.auth.service.TokenHandlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthorizationController {

    private final AuthorizationService authorizationService;
    private final TokenHandlerService tokenHandlerService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequestDTO loginRequestDTO) {
        return ResponseEntity.ok(authorizationService.login(loginRequestDTO));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthorizationResponseDTO> refresh(
            @RequestBody RefreshTokenRequestDTO refreshTokenRequestDTO) {
        return ResponseEntity.ok(tokenHandlerService.refreshToken(refreshTokenRequestDTO));
    }

    @PostMapping("/revoke")
    public ResponseEntity<?> revoke(@RequestBody RefreshTokenRequestDTO request) {
        tokenHandlerService.revokeToken(request.getRefreshToken());
        return ResponseEntity.ok(Map.of("message", "Token revoked successfully"));
    }

}
