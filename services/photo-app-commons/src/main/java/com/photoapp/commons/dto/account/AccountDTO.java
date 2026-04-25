package com.photoapp.commons.dto.account;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountDTO {
    private Long id;
    private Long userId;
    private String accountName;
    private AccountType accountType;
    private Boolean activeAccount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
