package com.photoapp.users.entity;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum RoleAction {
    ASSIGN,
    REMOVE;

    @JsonCreator
    public static RoleAction fromString(String value) {
        return RoleAction.valueOf(value.toUpperCase());
    }
}
