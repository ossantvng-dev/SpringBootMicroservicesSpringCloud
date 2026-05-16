package com.photoapp.auth.service;

import com.photoapp.auth.dto.AuthorizationResponseDTO;
import com.photoapp.auth.dto.LoginRequestDTO;

/**
 * AuthorizationService defines the contract for handling user login
 * and issuing authorization tokens.

 * Responsibilities:
 * - login: authenticate a user with their credentials and issue
 *   an access token plus a refresh token.

 * IMPORTANT NOTES:
 * - The login process validates username and password against the
 *   user repository or external service.
 * - On success, it generates a short-lived access token (JWT) and
 *   a long-lived refresh token.
 * - The access token is used for every request until it expires.
 * - The refresh token can later be exchanged for a new access token.
 */
public interface AuthorizationService {

    /**
     * Authenticate a user with the provided login request.
     * @param loginRequestDTO contains username and password
     * @return response containing access token, refresh token,
     *         token type, and expiration details
     */
    AuthorizationResponseDTO login(LoginRequestDTO loginRequestDTO);

}
