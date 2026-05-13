package com.photoapp.auth.service;

import com.photoapp.auth.dto.LoginRequestDTO;

public interface AuthorizationService {

    String login(LoginRequestDTO loginRequestDTO);

}
