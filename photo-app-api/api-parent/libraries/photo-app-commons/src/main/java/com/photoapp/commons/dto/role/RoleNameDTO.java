package com.photoapp.commons.dto.role;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum RoleNameDTO {
    ROLE_USER,
    ROLE_ADMIN;

    @JsonCreator
    public static RoleNameDTO fromString(String value) {
        return RoleNameDTO.valueOf(value.toUpperCase());
    }

}
