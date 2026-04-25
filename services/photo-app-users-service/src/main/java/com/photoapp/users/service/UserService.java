package com.photoapp.users.service;

import com.photoapp.users.dto.CreateUserInputDTO;
import com.photoapp.users.dto.UpdateUserInputDTO;
import com.photoapp.users.dto.UpdateUserRolesInputDTO;
import com.photoapp.users.dto.UserDTO;
import org.springframework.data.domain.Page;

import java.util.Map;

public interface UserService {

    UserDTO register(CreateUserInputDTO createUserInputDTO);

    UserDTO update(Long id, UpdateUserInputDTO updateUserInputDTO);

    UserDTO findById(Long id);

    UserDTO findByEmail(String email);

    UserDTO findByUsername(String username);

    Page<UserDTO> findAll(Map<String, String> filters);

    UserDTO activateOrDeactivate(Long id, boolean active);

    UserDTO assignOrRemoveRole(Long userId, UpdateUserRolesInputDTO updateUserRolesInputDTO);

    void deleteById(Long id);

}
