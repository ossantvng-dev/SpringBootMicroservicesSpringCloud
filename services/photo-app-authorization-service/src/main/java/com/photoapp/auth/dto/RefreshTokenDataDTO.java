package com.photoapp.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RefreshTokenDataDTO {

    private String userId;

    private long expiryTime;

}
