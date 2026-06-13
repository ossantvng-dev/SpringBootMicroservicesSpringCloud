package com.photoapp.auth.controller;

import com.photoapp.auth.dto.AuthorizationResponseDTO;
import com.photoapp.auth.dto.LoginRequestDTO;
import com.photoapp.auth.dto.RefreshTokenRequestDTO;
import com.photoapp.auth.service.AuthorizationService;
import com.photoapp.auth.service.TokenHandlerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthorizationController {

    private final AuthorizationService authorizationService;
    private final TokenHandlerService tokenHandlerService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequestDTO loginRequestDTO) {
        log.info("HTTP POST /auth/login - login request received username={}", loginRequestDTO.getUsername());
        var response = authorizationService.login(loginRequestDTO);
        log.info("HTTP POST /auth/login - login success username={}", loginRequestDTO.getUsername());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthorizationResponseDTO> refresh(@RequestBody RefreshTokenRequestDTO refreshTokenRequestDTO) {
        log.info("HTTP POST /auth/refresh - refresh token request received");
        var response = tokenHandlerService.refreshToken(refreshTokenRequestDTO);
        log.info("HTTP POST /auth/refresh - refresh token success");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/revoke")
    public ResponseEntity<?> revoke(@RequestBody RefreshTokenRequestDTO request) {
        log.info("HTTP POST /auth/revoke - revoke token request received");
        tokenHandlerService.revokeToken(request.getRefreshToken());
        log.info("HTTP POST /auth/revoke - revoke token success");
        return ResponseEntity.ok(Map.of("message", "Token revoked successfully"));
    }
}