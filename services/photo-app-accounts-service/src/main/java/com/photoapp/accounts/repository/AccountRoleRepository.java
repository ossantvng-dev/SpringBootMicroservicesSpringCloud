package com.photoapp.accounts.repository;

import com.photoapp.accounts.entity.AccountRole;
import com.photoapp.accounts.entity.AccountRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountRoleRepository extends JpaRepository<AccountRole, AccountRoleId> {

    List<AccountRole> findByIdAccountId(Long accountId);

    List<AccountRole> findByIdRoleId(Long roleId);

}
