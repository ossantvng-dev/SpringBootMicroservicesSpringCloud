package com.photoapp.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/*
    What authorization-service stores about an issued refresh token.

    username is held alongside userId because the refresh flow must re-identify the
    user WITHOUT any caller-supplied credential. The only unauthenticated lookup
    users-service exposes is GET /users/username/{username}, so the username is the
    key that makes a credential-free refresh possible.
 */
@Data
@AllArgsConstructor
public class RefreshTokenDataDTO {

    private String userId;

    private String username;

    private long expiryTime;

}
