package com.photoapp.users.service.impl;

import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.users.dto.CreateUserInputDTO;
import com.photoapp.users.dto.UpdateUserInputDTO;
import com.photoapp.users.dto.UserDTO;
import com.photoapp.users.dto.UserFilterDTO;
import com.photoapp.users.entity.User;
import com.photoapp.users.repository.UserRepository;
import com.photoapp.users.service.UserService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static com.photoapp.commons.util.FilterBuilderUtil.mapToFilter;
import static com.photoapp.commons.util.NormalizationUtil.normalizeInputDTO;
import static com.photoapp.users.repository.specification.UserSpecification.fromFilter;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final ModelMapper modelMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserDTO register(CreateUserInputDTO createUserInputDTO) {
        CreateUserInputDTO inputDTO = normalizeInputDTO(createUserInputDTO);
        if (userRepository.existsByEmailAndUsername(inputDTO.getEmail(),
                inputDTO.getUsername())) {
            throw new ApplicationException("User already registered", HttpStatus.BAD_REQUEST);
        } else {
            User newUser = modelMapper.map(inputDTO, User.class);
            newUser.setPasswordHash(passwordEncoder.encode(inputDTO.getPassword()));
            return modelMapper.map(userRepository.save(newUser), UserDTO.class);
        }
    }

    @Override
    @Transactional
    public UserDTO update(Long id, UpdateUserInputDTO updateUserInputDTO) {
        return userRepository.findById(id)
                .map(existingUser -> modelMapper.map(
                        userRepository.save(validateAndSetUser(existingUser, updateUserInputDTO)),
                        UserDTO.class))
                .orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO findById(Long id) {
        return userRepository.findById(id)
                .map(existingUser -> modelMapper.map(existingUser, UserDTO.class))
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
    public UserDTO findByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(existingUser -> modelMapper.map(existingUser, UserDTO.class))
                .orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserDTO> findAll(Map<String, String> filters, Pageable pageable) {
        return userRepository.findAll(fromFilter(mapToFilter(filters, UserFilterDTO.class)), pageable)
                .map(user -> modelMapper.map(user, UserDTO.class));
    }

    @Override
    @Transactional
    public UserDTO activateOrDeactivate(Long id, boolean active) {
        return userRepository.findById(id).map(existingUser -> {
            existingUser.setActiveUser(active);
            return modelMapper.map(userRepository.save(existingUser), UserDTO.class);
        }).orElseThrow(() -> new ApplicationException("User not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (userRepository.existsById(id)) {
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

}
