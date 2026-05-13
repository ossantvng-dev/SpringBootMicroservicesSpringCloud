package com.photoapp.entity;

import com.photoapp.commons.dto.account.AccountType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account extends BaseEntity {

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "account_type", nullable = false)
    private AccountType accountType = AccountType.BASIC;

    @Builder.Default
    @Column(name = "active_account", nullable = false)
    private Boolean activeAccount = true;

    @Column(name = "user_id", nullable = false)
    private Long userId;

}
