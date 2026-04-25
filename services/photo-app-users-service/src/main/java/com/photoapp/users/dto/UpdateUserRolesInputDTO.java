package com.photoapp.users.dto;

import com.photoapp.users.entity.RoleAction;
import com.photoapp.users.entity.RoleName;
import jakarta.validation.constraints.NotBlank;
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
    private Set<@NotBlank RoleName> roles;

}

