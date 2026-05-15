package com.photoapp.users.service.impl;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.commons.dto.role.RoleAction;
import com.photoapp.commons.dto.role.RoleName;
import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.entity.Role;
import com.photoapp.entity.User;
import com.photoapp.feign.client.AccountFeignClient;
import com.photoapp.feign.client.AlbumFeignClient;
import com.photoapp.feign.client.PhotoFeignClient;
import com.photoapp.security.service.CurrentUserService;
import com.photoapp.users.dto.CreateUserInputDTO;
import com.photoapp.users.dto.UpdateUserInputDTO;
import com.photoapp.users.dto.UpdateUserRolesInputDTO;
import com.photoapp.users.dto.UserFilterDTO;
import com.photoapp.users.repository.RoleRepository;
import com.photoapp.users.repository.UserRepository;
import com.photoapp.users.service.UserService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.photoapp.commons.util.FilterBuilderUtil.mapToFilter;
import static com.photoapp.commons.util.NormalizationUtil.normalizeInputDTO;
import static com.photoapp.commons.util.PaginationUtil.mapToPageable;
import static com.photoapp.users.repository.specification.UserSpecification.fromFilter;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ModelMapper modelMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccountFeignClient accountFeignClient;
    private final AlbumFeignClient albumFeignClient;
    private final PhotoFeignClient photoFeignClient;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public UserDTO register(CreateUserInputDTO createUserInputDTO) {
        CreateUserInputDTO inputDTO = normalizeInputDTO(createUserInputDTO);
        if (userRepository.existsByEmailAndUsername(inputDTO.getEmail(), inputDTO.getUsername())) {
            throw new ApplicationException("User already registered", HttpStatus.BAD_REQUEST);
        } else {
            Role defaultRole = roleRepository.findByName(RoleName.ROLE_USER)
                    .orElseThrow(() -> new ApplicationException("Default role not found", HttpStatus.NOT_FOUND));

            Set<Role> roles = (createUserInputDTO.getRoles() != null && !createUserInputDTO.getRoles().isEmpty())
                    ? mapRoleNamesToRoles(createUserInputDTO.getRoles())
                    : Set.of(defaultRole);

            User newUser = modelMapper.map(inputDTO, User.class);
            newUser.setRoles(roles);
            newUser.setPasswordHash(passwordEncoder.encode(inputDTO.getPassword()));
            User savedUser = userRepository.save(newUser);
            return modelMapper.map(savedUser, UserDTO.class);
        }
    }

    @Override
    @Transactional
    public UserDTO update(Long id, UpdateUserInputDTO updateUserInputDTO) {
        if (currentUserService.canAccessResource(String.valueOf(id))) {
            return userRepository.findById(id).map(existingUser -> {
                User validatedUser = validateAndSetUser(existingUser, updateUserInputDTO);
                User savedUser = userRepository.saveAndFlush(validatedUser);
                return modelMapper.map(savedUser, UserDTO.class);
            }).orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
        } else {
            throw new ApplicationException("You can only update your own user data", HttpStatus.FORBIDDEN);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO findById(Long id) {
        return userRepository.findById(id)
                .map(existingUser -> {
                    if (currentUserService.canAccessResource(String.valueOf(id))) {
                        return modelMapper.map(existingUser, UserDTO.class);
                    } else {
                        throw new ApplicationException("You can only view your own user data", HttpStatus.FORBIDDEN);
                    }
                })
                .orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO findByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(existingUser -> modelMapper.map(existingUser, UserDTO.class))
                .orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public User findByUsernameAndActiveUser(String username, boolean activeUser) {
        return userRepository.findByUsernameAndActiveUser(username, activeUser)
                .orElseThrow(() -> new ApplicationException("User not found or inactive", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserDTO> findAll(Map<String, String> filters) {
        return userRepository.findAll(
                fromFilter(mapToFilter(filters, UserFilterDTO.class)),
                mapToPageable(filters)
        ).map(user -> modelMapper.map(user, UserDTO.class));
    }

    @Override
    @Transactional
    public UserDTO activateOrDeactivate(Long id, boolean activate) {
        return userRepository.findById(id).map(existingUser -> {
            existingUser.setActiveUser(activate);
            return modelMapper.map(userRepository.saveAndFlush(existingUser), UserDTO.class);
        }).orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public UserDTO assignOrRemoveRole(Long id, UpdateUserRolesInputDTO updateUserRolesInputDTO) {
        return userRepository.findById(id)
                .map(existingUser -> {
                    if (!existingUser.getActiveUser()) {
                        throw new ApplicationException("User must be active", HttpStatus.FORBIDDEN);
                    }
                    Set<Role> roles = mapRoleNamesToRoles(updateUserRolesInputDTO.getRoles());
                    if (updateUserRolesInputDTO.getAction() == RoleAction.ASSIGN) {
                        existingUser.getRoles().addAll(roles);
                    } else if (updateUserRolesInputDTO.getAction() == RoleAction.REMOVE) {
                        existingUser.getRoles().removeAll(roles);
                        if (existingUser.getRoles().isEmpty()) {
                            throw new ApplicationException("User must have at least one role", HttpStatus.BAD_REQUEST);
                        }
                    }
                    return modelMapper.map(userRepository.saveAndFlush(existingUser), UserDTO.class);
                })
                .orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    public boolean existsById(Long id) { return userRepository.existsById(id); }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (userRepository.existsById(id)) {

            List<Long> accountIds = accountFeignClient
                    .findAll(Map.of("userId", String.valueOf(id)))
                    .map(AccountDTO::getId)
                    .toList();


            String accountIdsFilterParam = accountIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));

            if (!accountIds.isEmpty()) {

                List<Long> albumIds = albumFeignClient
                        .findAll(Map.of("accountIds", accountIdsFilterParam))
                        .map(AlbumDTO::getId)
                        .toList();

                if (!albumIds.isEmpty()) {
                    photoFeignClient.deleteByAlbumIds(albumIds);
                }

                albumFeignClient.deleteByAccountIds(accountIds);

                accountFeignClient.deleteByUserId(id);

            }
            userRepository.deleteById(id);

        } else {
            throw new ApplicationException("User not found", HttpStatus.NOT_FOUND);
        }
    }

    private User validateAndSetUser(User existingUser, UpdateUserInputDTO updateUserInputDTO) {

        UpdateUserInputDTO dto = normalizeInputDTO(updateUserInputDTO);

        boolean updated = false;

        String newFirstName = dto.getFirstName();
        String newLastName  = dto.getLastName();
        String newEmail     = dto.getEmail();

        if (!existingUser.getFirstName().equals(newFirstName)) {
            existingUser.setFirstName(newFirstName);
            updated = true;
        }

        if (!existingUser.getLastName().equals(newLastName)) {
            existingUser.setLastName(newLastName);
            updated = true;
        }

        if (!existingUser.getEmail().equals(newEmail)) {
            if (userRepository.existsByEmail(newEmail)) {
                throw new ApplicationException("Email already registered", HttpStatus.CONFLICT);
            }
            existingUser.setEmail(newEmail);
            updated = true;
        }

        if (!updated) {
            throw new ApplicationException("No changes detected", HttpStatus.BAD_REQUEST);
        }

        return existingUser;
    }

    private Set<Role> mapRoleNamesToRoles(Set<RoleName> roleNames) {
        return roleNames.stream()
                .map(roleName -> roleRepository.findByName(roleName)
                        .orElseThrow(() -> new ApplicationException("Role not found: " + roleName.name(),
                                HttpStatus.NOT_FOUND)))
                .collect(Collectors.toSet());
    }


}
