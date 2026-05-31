package com.photoapp.users.repository;

import com.photoapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUsernameAndActiveUser(String username, boolean activeUser);

    boolean existsByEmail(String email);

    boolean existsByEmailAndUsername(String email, String username);

}
