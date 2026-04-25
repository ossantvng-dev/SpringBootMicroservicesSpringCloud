package com.photoapp.accounts.repository.specification;

import com.photoapp.accounts.dto.AccountFilterDTO;
import com.photoapp.accounts.entity.Account;
import com.photoapp.accounts.entity.Account_;
import org.springframework.data.jpa.domain.Specification;

public class AccountSpecification {

    public static Specification<Account> fromFilter(AccountFilterDTO filter) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();

            if (filter.getAccountName() != null && !filter.getAccountName().isBlank()) {
                predicates.getExpressions().add(
                        cb.like(cb.lower(root.get(Account_.accountName)), "%" + filter.getAccountName().toLowerCase() + "%")
                );
            }

            if (filter.getAccountType() != null) {
                predicates.getExpressions().add(
                        cb.equal(root.get(Account_.accountType), filter.getAccountType())
                );
            }

            if (filter.getActiveAccount() != null) {
                predicates.getExpressions().add(
                        cb.equal(root.get(Account_.activeAccount), filter.getActiveAccount())
                );
            }

            if (filter.getUserId() != null) {
                predicates.getExpressions().add(
                        cb.equal(root.get(Account_.userId), filter.getUserId())
                );
            }

            // Date ranges for createdAt
            if (filter.getCreatedStart() != null && filter.getCreatedEnd() != null) {
                predicates.getExpressions().add(
                        cb.between(root.get(Account_.createdAt), filter.getCreatedStart(), filter.getCreatedEnd())
                );
            } else if (filter.getCreatedStart() != null) {
                predicates.getExpressions().add(
                        cb.greaterThanOrEqualTo(root.get(Account_.createdAt), filter.getCreatedStart())
                );
            } else if (filter.getCreatedEnd() != null) {
                predicates.getExpressions().add(
                        cb.lessThanOrEqualTo(root.get(Account_.createdAt), filter.getCreatedEnd())
                );
            }

            // Date ranges for updatedAt
            if (filter.getUpdatedStart() != null && filter.getUpdatedEnd() != null) {
                predicates.getExpressions().add(
                        cb.between(root.get(Account_.updatedAt), filter.getUpdatedStart(), filter.getUpdatedEnd())
                );
            } else if (filter.getUpdatedStart() != null) {
                predicates.getExpressions().add(
                        cb.greaterThanOrEqualTo(root.get(Account_.updatedAt), filter.getUpdatedStart())
                );
            } else if (filter.getUpdatedEnd() != null) {
                predicates.getExpressions().add(
                        cb.lessThanOrEqualTo(root.get(Account_.updatedAt), filter.getUpdatedEnd())
                );
            }

            return predicates;
        };
    }
}

