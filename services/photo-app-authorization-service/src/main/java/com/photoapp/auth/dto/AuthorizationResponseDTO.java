package com.photoapp.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthorizationResponseDTO {

    private String accessToken;

    private String refreshToken;

    private String tokenType;

    // Value in seconds
    private long expiresIn;

    private long refreshTokenExpiresIn;

}
