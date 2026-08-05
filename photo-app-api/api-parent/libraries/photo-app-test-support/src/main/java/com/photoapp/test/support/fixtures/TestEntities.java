package com.photoapp.test.support.fixtures;

import com.photoapp.entity.Account;
import com.photoapp.entity.AccountType;
import com.photoapp.entity.Album;
import com.photoapp.entity.Photo;
import com.photoapp.entity.Role;
import com.photoapp.entity.RoleName;
import com.photoapp.entity.User;

import java.util.Set;

/*
    Entity fixtures.

    Each factory returns a Lombok builder, not a finished entity, so a test can override only
    the field it cares about and let everything else stay at a sane default. That keeps the
    intent of a test visible: whatever a test sets is what the test is about.

    Note what these builders CANNOT set: id, version, createdAt and updatedAt live on
    BaseEntity, and Lombok's @Builder does not expose inherited fields. Persistence tests
    should let the database assign the id; unit tests that need one must set it after building.
    This is the same constraint that stops the MapStruct mappers declaring
    @Mapping(target = "id", ignore = true).
 */
public final class TestEntities {

    private TestEntities() {
    }

    // ---------------------------------------------------------------- users

    public static User.UserBuilder user() {
        return User.builder()
                .firstName("Test")
                .lastName("User")
                .username("testuser")
                .email("testuser@photoapp.com")
                // The BCrypt hash of "generic", matching the Liquibase seed data.
                .passwordHash("$2a$12$JNlutweXUWEiUTqt1Z8K6uxB2r0oOuRpEqC520v4LyWsR04FEs1Tm")
                .activeUser(true)
                .roles(Set.of(role(RoleName.ROLE_USER).build()));
    }

    public static User.UserBuilder adminUser() {
        return user()
                .username("admin")
                .email("admin@photoapp.com")
                .roles(Set.of(role(RoleName.ROLE_ADMIN).build()));
    }

    public static User.UserBuilder inactiveUser() {
        return user().username("inactive").email("inactive@photoapp.com").activeUser(false);
    }

    // ---------------------------------------------------------------- roles

    public static Role.RoleBuilder role(RoleName name) {
        return Role.builder().name(name);
    }

    // ------------------------------------------------------------- accounts

    public static Account.AccountBuilder account() {
        return Account.builder()
                .accountName("Test Account")
                .accountType(AccountType.BASIC)
                .activeAccount(true)
                .userId(1L);
    }

    public static Account.AccountBuilder premiumAccount() {
        return account().accountName("Premium Account").accountType(AccountType.PREMIUM);
    }

    // --------------------------------------------------------------- albums

    public static Album.AlbumBuilder album() {
        return Album.builder()
                .accountId(1L)
                .title("Test Album")
                .description("Created by a test fixture")
                .activeAlbum(true);
    }

    // --------------------------------------------------------------- photos

    public static Photo.PhotoBuilder photo() {
        return Photo.builder()
                .albumId(1L)
                .fileName("test-photo.jpg")
                .fileUrl("https://example.invalid/test-photo.jpg")
                .activePhoto(true);
    }
}
