package com.photoapp.commons.dto.user;

import com.photoapp.commons.dto.role.RoleDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String username;
    private String email;
    private Boolean activeUser;
    private Set<RoleDTO> roles;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
