package com.photoapp.accounts.entity;

import com.photoapp.commons.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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

    @Builder.Default
    @Column(name = "active_account", nullable = false)
    private Boolean activeAccount = true;

    @Column(name = "user_id", nullable = false)
    private Long userId;

}
