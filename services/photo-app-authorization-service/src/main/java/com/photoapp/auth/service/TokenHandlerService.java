package com.photoapp.auth.service;

import com.photoapp.auth.dto.AuthorizationResponseDTO;
import com.photoapp.auth.dto.RefreshTokenRequestDTO;

public interface TokenHandlerService {

    String generateRefreshToken(String userId);

    AuthorizationResponseDTO refreshToken(RefreshTokenRequestDTO refreshTokenRequestDTO);

    void revokeToken(String refreshToken);

}
