package com.photoapp.accounts.entity;

import com.photoapp.commons.entity.BaseEntity;
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
    private com.photoapp.commons.dto.account.AccountType accountType = com.photoapp.commons.dto.account.AccountType.BASIC;

    @Builder.Default
    @Column(name = "active_account", nullable = false)
    private Boolean activeAccount = true;

    @Column(name = "user_id", nullable = false)
    private Long userId;

}
