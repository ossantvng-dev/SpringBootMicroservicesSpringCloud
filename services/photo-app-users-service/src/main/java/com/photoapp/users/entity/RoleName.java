package com.photoapp.users.entity;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum RoleName {
    ROLE_USER,
    ROLE_ADMIN;

    @JsonCreator
    public static RoleName fromString(String value) {
        return RoleName.valueOf(value.toUpperCase());
    }

}
