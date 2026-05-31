package com.photoapp.accounts.dto;

import com.photoapp.commons.dto.account.AccountTypeDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountFilterDTO {
    private String accountName;
    private AccountTypeDTO accountTypeDTO;
    private Boolean activeAccount;
    private Long userId;
    private LocalDateTime createdStart;
    private LocalDateTime createdEnd;
    private LocalDateTime updatedStart;
    private LocalDateTime updatedEnd;
}
