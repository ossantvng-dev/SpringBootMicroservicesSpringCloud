package com.photoapp.auth.service.impl;

import com.photoapp.auth.dto.AuthorizationResponseDTO;
import com.photoapp.auth.dto.LoginRequestDTO;
import com.photoapp.auth.service.AuthorizationService;
import com.photoapp.auth.service.TokenHandlerService;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.entity.User;
import com.photoapp.feign.client.UserFeignClient;
import com.photoapp.security.provider.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationServiceImpl implements AuthorizationService {

    private final UserFeignClient userFeignClient;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenHandlerService refreshTokenService;

    @Override
    public AuthorizationResponseDTO login(LoginRequestDTO loginRequestDTO) {
        log.info("AUTH LOGIN started username={}", loginRequestDTO.getUsername());
        log.info("AUTH LOGIN fetching user from users-service username={}", loginRequestDTO.getUsername());

        User user = userFeignClient.findByUsernameAndActiveUser(loginRequestDTO.getUsername());

        if (user == null) {
            log.warn("AUTH LOGIN user not found username={}", loginRequestDTO.getUsername());
            throw new ApplicationException("User not found or inactive", HttpStatus.UNAUTHORIZED);
        }

        log.info("AUTH LOGIN user found userId={} username={}", user.getId(), loginRequestDTO.getUsername());

        log.info("AUTH LOGIN validating password userId={}", user.getId());

        if (!passwordEncoder.matches(loginRequestDTO.getPassword(), user.getPasswordHash())) {
            log.warn("AUTH LOGIN invalid credentials userId={} username={}", user.getId(), user.getUsername());
            throw new ApplicationException("Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        log.info("AUTH LOGIN building scopes userId={}", user.getId());

        List<String> scopes = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList());

        log.info("AUTH LOGIN generating access token userId={}", user.getId());

        String accessToken = jwtTokenProvider
                .generateToken(user.getId().toString(), user.getUsername(), scopes);

        log.info("AUTH LOGIN generating refresh token userId={}", user.getId());

        String refreshToken = refreshTokenService.generateRefreshToken(user.getId().toString());

        long accessTokenExpiresIn = jwtTokenProvider.getValidityInMillis() / 1000;
        long refreshTokenExpiresIn = TokenHandlerServiceImpl.REFRESH_TOKEN_VALIDITY / 1000;

        log.info("AUTH LOGIN success userId={} username={}", user.getId(), user.getUsername());

        return new AuthorizationResponseDTO(
                accessToken,
                refreshToken,
                "Bearer",
                accessTokenExpiresIn,
                refreshTokenExpiresIn
        );
    }

}
