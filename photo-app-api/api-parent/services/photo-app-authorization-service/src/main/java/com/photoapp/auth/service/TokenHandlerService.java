package com.photoapp.auth.service;

import com.photoapp.auth.dto.AuthorizationResponseDTO;
import com.photoapp.auth.dto.RefreshTokenRequestDTO;

/**
 * TokenHandlerService defines the contract for handling refresh and access tokens.

 * Responsibilities:
 * - generateRefreshToken: issue a new refresh token tied to a user.
 * - refreshToken: exchange a valid refresh token for a new access token.
 * - revokeToken: invalidate a refresh token so it can no longer be used.

 * IMPORTANT NOTES:
 * - Revoking a refresh token DOES NOT invalidate already-issued access tokens.
 * - Access tokens remain valid until their expiration time (exp claim in the JWT).
 * - After revoke, the user cannot renew or extend their session with that refresh token.
 * - If immediate invalidation of access tokens is required, an additional mechanism
 *   such as token blacklisting or short-lived access tokens must be implemented.
 */
public interface TokenHandlerService {

    /**
     * Generate and persist a refresh token associated with the given user.
     * @param userId the identifier of the user
     * @return the newly generated refresh token
     */
    String generateRefreshToken(String userId);

    /**
     * Exchange a valid refresh token for a new access token.
     * @param refreshTokenRequestDTO contains the refresh token to be used
     * @return response containing new access and refresh tokens
     */
    AuthorizationResponseDTO refreshToken(RefreshTokenRequestDTO refreshTokenRequestDTO);

    /**
     * Revoke a refresh token, preventing it from being used to obtain new access tokens.
     * Note: existing access tokens remain valid until they expire naturally.
     * @param refreshToken the refresh token to revoke
     */
    void revokeToken(String refreshToken);

}
