package com.photoapp.users.service.impl;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.commons.dto.role.RoleAction;
import com.photoapp.commons.dto.role.RoleNameDTO;
import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.commons.mapper.UserMapper;
import com.photoapp.entity.Role;
import com.photoapp.entity.RoleName;
import com.photoapp.entity.User;
import com.photoapp.feign.client.AccountFeignClient;
import com.photoapp.feign.client.AlbumFeignClient;
import com.photoapp.feign.client.PhotoFeignClient;
import com.photoapp.security.service.CurrentUserService;
import com.photoapp.users.dto.CreateUserInputDTO;
import com.photoapp.users.dto.UpdateUserInputDTO;
import com.photoapp.users.dto.UpdateUserRolesInputDTO;
import com.photoapp.users.dto.UserFilterDTO;
import com.photoapp.users.mapper.UserInputMapper;
import com.photoapp.users.repository.RoleRepository;
import com.photoapp.users.repository.UserRepository;
import com.photoapp.users.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.photoapp.commons.dto.PagedResponseDTO;
import com.photoapp.commons.mapper.PagedResponseMapper;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final PagedResponseMapper pagedResponseMapper;
    private final UserInputMapper userInputMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccountFeignClient accountFeignClient;
    private final AlbumFeignClient albumFeignClient;
    private final PhotoFeignClient photoFeignClient;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public UserDTO register(CreateUserInputDTO createUserInputDTO) {
        log.info("Registering new user username={} email={}", createUserInputDTO.getUsername(), createUserInputDTO.getEmail());
        CreateUserInputDTO inputDTO = normalizeInputDTO(createUserInputDTO);
        if (userRepository.existsByEmailAndUsername(inputDTO.getEmail(), inputDTO.getUsername())) {
            throw new ApplicationException("User already registered", HttpStatus.BAD_REQUEST);
        }
        Role defaultRole = roleRepository.findByName(RoleName.ROLE_USER)
                .orElseThrow(() -> new ApplicationException("Default role not found", HttpStatus.NOT_FOUND));

        Set<Role> roles = (createUserInputDTO.getRoles() != null && !createUserInputDTO.getRoles().isEmpty())
                ? mapRoleNamesToRoles(createUserInputDTO.getRoles())
                : Set.of(defaultRole);

        User newUser = userInputMapper.toEntity(inputDTO);
        newUser.setRoles(roles);
        newUser.setPasswordHash(passwordEncoder.encode(inputDTO.getPassword()));
        User savedUser = userRepository.save(newUser);
        log.info("User registered successfully userId={}", savedUser.getId());
        return userMapper.toDTO(savedUser);
    }

    @Override
    @Transactional
    public UserDTO update(Long id, UpdateUserInputDTO updateUserInputDTO) {
        log.info("Updating user userId={}", id);
        if (!currentUserService.canAccessResource(String.valueOf(id))) {
            throw new ApplicationException("You can only update your own user data", HttpStatus.FORBIDDEN);
        }
        return userRepository.findById(id).map(existingUser -> {
            User validatedUser = validateAndSetUser(existingUser, updateUserInputDTO);
            User savedUser = userRepository.saveAndFlush(validatedUser);
            log.info("User updated successfully userId={}", id);
            return userMapper.toDTO(savedUser);
        }).orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO findById(Long id) {
        log.debug("Finding user by id={}", id);
        return userRepository.findById(id)
                .map(existingUser -> {
                    if (!currentUserService.canAccessResource(String.valueOf(id))) {
                        throw new ApplicationException("You can only view your own user data", HttpStatus.FORBIDDEN);
                    }
                    log.info("User retrieved successfully userId={}", id);
                    return userMapper.toDTO(existingUser);
                })
                .orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO findByEmail(String email) {
        log.debug("Finding user by email={}", email);
        return userRepository.findByEmail(email)
                .map(existingUser -> {
                    log.info("User retrieved successfully email={}", email);
                    return userMapper.toDTO(existingUser);
                })
                .orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public User findByUsernameAndActiveUser(String username, boolean activeUser) {
        log.debug("Finding user by username={} activeUser={}", username, activeUser);
        return userRepository.findByUsernameAndActiveUser(username, activeUser)
                .orElseThrow(() -> new ApplicationException("User not found or inactive", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponseDTO<UserDTO> findAll(Map<String, String> filters) {
        log.debug("Finding all users filters={}", filters);
        PagedResponseDTO<UserDTO> result = pagedResponseMapper.toPagedResponse(
                userRepository.findAll(
                        fromFilter(mapToFilter(filters, UserFilterDTO.class)),
                        mapToPageable(filters)
                ),
                userMapper::toDTO
        );
        log.info("Users listed successfully count={}", result.getTotalElements());
        return result;
    }

    @Override
    @Transactional
    public UserDTO activateOrDeactivate(Long id, boolean activate) {
        log.info("Updating user active state userId={} activate={}", id, activate);
        return userRepository.findById(id).map(existingUser -> {
            existingUser.setActiveUser(activate);
            User updated = userRepository.saveAndFlush(existingUser);
            log.info("User state updated successfully userId={} active={}", id, activate);
            return userMapper.toDTO(updated);
        }).orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public UserDTO assignOrRemoveRole(Long id, UpdateUserRolesInputDTO updateUserRolesInputDTO) {
        log.info("Updating roles for userId={} action={}", id, updateUserRolesInputDTO.getAction());
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
                    User updated = userRepository.saveAndFlush(existingUser);
                    log.info("User roles updated successfully userId={}", id);
                    return userMapper.toDTO(updated);
                })
                .orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    public boolean existsById(Long id) {
        log.debug("Checking if user exists userId={}", id);
        return userRepository.existsById(id);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        log.warn("Deleting user by id={}", id);
        if (!userRepository.existsById(id)) {
            throw new ApplicationException("User not found", HttpStatus.NOT_FOUND);
        }

        /*
            These two findAll calls are easy to miss when auditing which Feign methods are live:
            the receiver and the method sit on different lines, so a single-line grep for
            "accountFeignClient.findAll(" matches nothing. Both are load-bearing - they collect
            the ids the cascade below deletes.
         */
        List<Long> accountIds = accountFeignClient
                .findAll(Map.of("userId", String.valueOf(id)))
                .getContent().stream()
                .map(AccountDTO::getId)
                .toList();

        String accountIdsFilterParam = accountIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));

        if (!accountIds.isEmpty()) {
            List<Long> albumIds = albumFeignClient
                    .findAll(Map.of("accountIds", accountIdsFilterParam))
                    .getContent().stream()
                    .map(AlbumDTO::getId)
                    .toList();

            if (!albumIds.isEmpty()) {
                photoFeignClient.deleteByAlbumIds(albumIds);
            }

            albumFeignClient.deleteByAccountIds(accountIds);
            accountFeignClient.deleteByUserId(id);
        }

        userRepository.deleteById(id);
        log.info("User deleted successfully userId={}", id);
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

    private Set<Role> mapRoleNamesToRoles(Set<RoleNameDTO> roleNames) {
        return roleNames.stream()
                .map(roleNameDTO -> roleRepository.findByName(RoleName.valueOf(roleNameDTO.name()))
                        .orElseThrow(() -> new ApplicationException("Role not found: " + roleNameDTO.name(),
                                HttpStatus.NOT_FOUND)))
                .collect(Collectors.toSet());
    }
}
