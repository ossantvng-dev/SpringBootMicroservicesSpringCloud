package com.photoapp.auth.service.impl;

import com.photoapp.auth.dto.AuthorizationResponseDTO;
import com.photoapp.auth.dto.RefreshTokenDataDTO;
import com.photoapp.auth.dto.RefreshTokenRequestDTO;
import com.photoapp.auth.service.TokenHandlerService;
import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.client.UserFeignClient;
import com.photoapp.security.provider.JwtTokenProvider;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class TokenHandlerServiceImpl implements TokenHandlerService {

    // 30 days
    public static final long REFRESH_TOKEN_VALIDITY = 30L * 24 * 60 * 60 * 1000;

    private final Map<String, RefreshTokenDataDTO> refreshTokens = new ConcurrentHashMap<>();
    private final UserFeignClient userFeignClient;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public String generateRefreshToken(String userId) {
        String token = UUID.randomUUID().toString();
        long expiryTime = System.currentTimeMillis() + REFRESH_TOKEN_VALIDITY;
        refreshTokens.put(token, new RefreshTokenDataDTO(userId, expiryTime));
        return token;
    }

    @Override
    public AuthorizationResponseDTO refreshToken(RefreshTokenRequestDTO refreshTokenRequestDTO) {
        RefreshTokenDataDTO data = refreshTokens.get(refreshTokenRequestDTO.getRefreshToken());
        if (data == null || data.getExpiryTime() <= System.currentTimeMillis()) {
            throw new ApplicationException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
        }

        UserDTO user = userFeignClient.findById(Long.valueOf(data.getUserId()));
        if (user == null || !Boolean.TRUE.equals(user.getActiveUser())) {
            throw new ApplicationException("User not found or inactive", HttpStatus.UNAUTHORIZED);
        }

        List<String> scopes = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList());

        String newAccessToken = jwtTokenProvider
                .generateToken(user.getId().toString(), user.getUsername(), scopes);

        return new AuthorizationResponseDTO(
                newAccessToken,
                refreshTokenRequestDTO.getRefreshToken(),
                "Bearer",
                jwtTokenProvider.getValidityInMillis() / 1000,
                (data.getExpiryTime() - System.currentTimeMillis()) / 1000
        );
    }

    @Override
    public void revokeToken(String refreshToken) {
        RefreshTokenDataDTO data = refreshTokens.get(refreshToken);
        if (data != null) {
            // set as expired
            data.setExpiryTime(System.currentTimeMillis());
            refreshTokens.put(refreshToken, data);
        }
    }

}
