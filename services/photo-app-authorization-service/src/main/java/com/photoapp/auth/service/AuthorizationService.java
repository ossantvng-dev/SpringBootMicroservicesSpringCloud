package com.photoapp.auth.service;

import com.photoapp.auth.dto.AuthorizationResponseDTO;
import com.photoapp.auth.dto.LoginRequestDTO;

public interface AuthorizationService {

    AuthorizationResponseDTO login(LoginRequestDTO loginRequestDTO);

}
