package com.photoapp.commons.dto.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAccountInputDTO {

    @NotNull(message = "User Id is required")
    private Long userId;

    @NotBlank(message = "Account name is required")
    @Size(max = 100, message = "Account must not exceed 100 characters")
    private String accountName;

    @NotNull(message = "Account type is required")
    private AccountType accountType;

}
