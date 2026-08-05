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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class TokenHandlerServiceImpl implements TokenHandlerService {

    // 30 days
    public static final long REFRESH_TOKEN_VALIDITY = 30L * 24 * 60 * 60 * 1000;

    private final Map<String, RefreshTokenDataDTO> refreshTokens = new ConcurrentHashMap<>();
    private final UserFeignClient userFeignClient;
    private final JwtTokenProvider jwtTokenProvider;
    /*
        Clock.systemUTC().millis() is specified as equivalent to System.currentTimeMillis(),
        which is what every call site below used, so production behaviour is unchanged.
        Lombok's @AllArgsConstructor picks this up: refreshTokens is an initialised final
        field and is excluded, so the generated constructor is
        (UserFeignClient, JwtTokenProvider, Clock) - still plain constructor injection.
     */
    private final Clock clock;

    @Override
    public String generateRefreshToken(String userId) {
        log.info("REFRESH TOKEN generation started userId={}", userId);
        String token = UUID.randomUUID().toString();
        long expiryTime = clock.millis() + REFRESH_TOKEN_VALIDITY;
        refreshTokens.put(token, new RefreshTokenDataDTO(userId, expiryTime));
        log.info("REFRESH TOKEN generated userId={}", userId);
        return token;
    }

    @Override
    public AuthorizationResponseDTO refreshToken(RefreshTokenRequestDTO refreshTokenRequestDTO) {
        log.info("REFRESH TOKEN request received token={}", maskToken(refreshTokenRequestDTO.getRefreshToken()));

        RefreshTokenDataDTO data = refreshTokens.get(refreshTokenRequestDTO.getRefreshToken());

        if (data == null) {
            log.warn("REFRESH TOKEN invalid token (not found)");
            throw new ApplicationException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
        }

        if (data.getExpiryTime() <= clock.millis()) {
            log.warn("REFRESH TOKEN expired userId={}", data.getUserId());
            throw new ApplicationException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
        }

        log.info("REFRESH TOKEN valid userId={}", data.getUserId());
        log.info("REFRESH TOKEN calling users-service userId={}", data.getUserId());

        UserDTO user = userFeignClient.findById(Long.valueOf(data.getUserId()));

        if (user == null || !Boolean.TRUE.equals(user.getActiveUser())) {
            log.warn("REFRESH TOKEN user inactive or not found userId={}", data.getUserId());
            throw new ApplicationException("User not found or inactive", HttpStatus.UNAUTHORIZED);
        }

        List<String> scopes = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList());

        log.info("REFRESH TOKEN generating new access token userId={}", user.getId());

        String newAccessToken = jwtTokenProvider
                .generateToken(user.getId().toString(), user.getUsername(), scopes);

        log.info("REFRESH TOKEN success userId={}", user.getId());

        return new AuthorizationResponseDTO(
                newAccessToken,
                refreshTokenRequestDTO.getRefreshToken(),
                "Bearer",
                jwtTokenProvider.getValidityInMillis() / 1000,
                (data.getExpiryTime() - clock.millis()) / 1000
        );
    }

    @Override
    public void revokeToken(String refreshToken) {
        log.info("REFRESH TOKEN revoke request received token={}", maskToken(refreshToken));
        RefreshTokenDataDTO data = refreshTokens.get(refreshToken);
        if (data != null) {
            // set as expired
            data.setExpiryTime(clock.millis());
            refreshTokens.put(refreshToken, data);
            log.info("REFRESH TOKEN revoked userId={}", data.getUserId());
        } else  {
            log.warn("REFRESH TOKEN revoke attempted for unknown token");
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }

}
