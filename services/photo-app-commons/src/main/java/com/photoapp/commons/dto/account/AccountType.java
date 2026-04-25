package com.photoapp.commons.dto.account;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AccountType {
    BASIC,
    PREMIUM;

    @JsonCreator
    public static AccountType fromString(String value) {
        return AccountType.valueOf(value.toUpperCase());
    }
}
