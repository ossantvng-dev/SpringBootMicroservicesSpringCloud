package com.photoapp.commons.dto.account;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AccountTypeDTO {
    BASIC,
    PREMIUM;

    @JsonCreator
    public static AccountTypeDTO fromString(String value) {
        return AccountTypeDTO.valueOf(value.toUpperCase());
    }
}
