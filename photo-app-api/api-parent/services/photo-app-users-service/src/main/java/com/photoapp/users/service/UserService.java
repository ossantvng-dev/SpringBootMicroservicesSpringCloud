package com.photoapp.users.service;

import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.entity.User;
import com.photoapp.users.dto.CreateUserInputDTO;
import com.photoapp.users.dto.UpdateUserInputDTO;
import com.photoapp.users.dto.UpdateUserRolesInputDTO;
import com.photoapp.commons.dto.PagedResponseDTO;

import java.util.Map;

public interface UserService {

    UserDTO register(CreateUserInputDTO createUserInputDTO);

    UserDTO update(Long id, UpdateUserInputDTO updateUserInputDTO);

    UserDTO findById(Long id);

    UserDTO findByEmail(String email);

    User findByUsernameAndActiveUser(String username, boolean activeUser);

    PagedResponseDTO<UserDTO> findAll(Map<String, String> filters);

    UserDTO activateOrDeactivate(Long id, boolean activate);

    UserDTO assignOrRemoveRole(Long userId, UpdateUserRolesInputDTO updateUserRolesInputDTO);

    boolean existsById(Long id);

    void deleteById(Long id);

}
