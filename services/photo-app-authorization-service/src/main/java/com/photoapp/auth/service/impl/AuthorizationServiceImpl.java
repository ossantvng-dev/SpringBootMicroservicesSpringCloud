package com.photoapp.auth.service.impl;

import com.photoapp.auth.dto.LoginRequestDTO;
import com.photoapp.auth.service.AuthorizationService;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.entity.User;
import com.photoapp.feign.UserFeignClient;
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

    @Override
    public String login(LoginRequestDTO loginRequestDTO) {
        // Find if active user exists
        User user = userFeignClient.findByUsernameAndActiveUser(loginRequestDTO.getUsername());

        if (user == null) {
            throw new ApplicationException("User not found or inactive", HttpStatus.UNAUTHORIZED);
        }

        // Compare password
        if (!passwordEncoder.matches(loginRequestDTO.getPassword(), user.getPasswordHash())) {
            throw new ApplicationException("Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        // Roles as scopes
        List<String> scopes = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList());

        // Generate JWT with userId as subject and username as claim
        return jwtTokenProvider.generateToken(user.getId().toString(), user.getUsername(), scopes);
    }
}
