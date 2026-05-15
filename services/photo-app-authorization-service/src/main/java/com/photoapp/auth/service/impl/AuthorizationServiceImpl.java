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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthorizationServiceImpl implements AuthorizationService {

    private final UserFeignClient userFeignClient;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenHandlerService refreshTokenService;

    @Override
    public AuthorizationResponseDTO login(LoginRequestDTO loginRequestDTO) {
        User user = userFeignClient.findByUsernameAndActiveUser(loginRequestDTO.getUsername());

        if (user == null) {
            throw new ApplicationException("User not found or inactive", HttpStatus.UNAUTHORIZED);
        }

        if (!passwordEncoder.matches(loginRequestDTO.getPassword(), user.getPasswordHash())) {
            throw new ApplicationException("Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        List<String> scopes = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList());

        String accessToken = jwtTokenProvider
                .generateToken(user.getId().toString(), user.getUsername(), scopes);

        String refreshToken = refreshTokenService.generateRefreshToken(user.getId().toString());

        long accessTokenExpiresIn = jwtTokenProvider.getValidityInMillis() / 1000;
        long refreshTokenExpiresIn = TokenHandlerServiceImpl.REFRESH_TOKEN_VALIDITY / 1000;

        return new AuthorizationResponseDTO(
                accessToken,
                refreshToken,
                "Bearer",
                accessTokenExpiresIn,
                refreshTokenExpiresIn
        );
    }

}
