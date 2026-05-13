package com.photoapp.users.dto;

import com.photoapp.commons.dto.role.RoleAction;
import com.photoapp.commons.dto.role.RoleName;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateUserRolesInputDTO {

    @NotNull(message = "Action must be provided")
    private RoleAction action;

    @NotEmpty(message = "Roles cannot be empty")
    private Set<@NotNull RoleName> roles;

}

