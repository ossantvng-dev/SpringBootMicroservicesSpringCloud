package com.photoapp.users.repository.specification;

import com.photoapp.users.dto.UserFilterDTO;
import com.photoapp.users.entity.User;
import com.photoapp.users.entity.User_;
import org.springframework.data.jpa.domain.Specification;

public class UserSpecification {

    public static Specification<User> fromFilter(UserFilterDTO filter) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (filter.getFirstName() != null && !filter.getFirstName().isBlank()) {
               predicates.getExpressions().add(
                       cb.like(cb.lower(root.get(User_.firstName)), "%" + filter.getFirstName().toLowerCase() + "%")
                );
            }
            if (filter.getLastName() != null && !filter.getLastName().isBlank()) {
                predicates.getExpressions().add(
                        cb.like(cb.lower(root.get(User_.lastName)), "%" + filter.getLastName().toLowerCase() + "%")
                );
            }
            if (filter.getUsername() != null && !filter.getUsername().isBlank()) {
                predicates.getExpressions().add(
                        cb.like(cb.lower(root.get(User_.username)), "%" + filter.getUsername().toLowerCase() + "%")
                );
            }
            if (filter.getEmail() != null && !filter.getEmail().isBlank()) {
                predicates.getExpressions().add(
                        cb.like(cb.lower(root.get(User_.email)), "%" + filter.getEmail().toLowerCase() + "%")
                );
            }
            if (filter.getActive() != null) {
                predicates.getExpressions().add(
                        cb.equal(root.get(User_.activeUser), filter.getActive())
                );
            }
            // Date ranges for createdAt
            if (filter.getCreatedStart() != null && filter.getCreatedEnd() != null) {
                predicates.getExpressions().add(
                        cb.between(root.get(User_.createdAt), filter.getCreatedStart(), filter.getCreatedEnd())
                );
            } else if (filter.getCreatedStart() != null) {
                predicates.getExpressions().add(
                        cb.greaterThanOrEqualTo(root.get(User_.createdAt), filter.getCreatedStart())
                );
            } else if (filter.getCreatedEnd() != null) {
                predicates.getExpressions().add(
                        cb.lessThanOrEqualTo(root.get(User_.createdAt), filter.getCreatedEnd())
                );
            }
            // Date ranges for updatedAt
            if (filter.getUpdatedStart() != null && filter.getUpdatedEnd() != null) {
                predicates.getExpressions().add(
                        cb.between(root.get(User_.updatedAt), filter.getUpdatedStart(), filter.getUpdatedEnd())
                );
            } else if (filter.getUpdatedStart() != null) {
                predicates.getExpressions().add(
                        cb.greaterThanOrEqualTo(root.get(User_.updatedAt), filter.getUpdatedStart())
                );
            } else if (filter.getUpdatedEnd() != null) {
                predicates.getExpressions().add(
                        cb.lessThanOrEqualTo(root.get(User_.updatedAt), filter.getUpdatedEnd())
                );
            }
            return predicates;
        };
    }
}
