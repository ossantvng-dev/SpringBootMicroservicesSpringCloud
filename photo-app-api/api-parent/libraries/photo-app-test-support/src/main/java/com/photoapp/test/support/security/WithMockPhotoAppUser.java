package com.photoapp.test.support.security;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Populates the SecurityContext with a real {@code CustomUserPrincipal}.
 *
 * <p>Spring's {@code @WithMockUser} is not usable here: it installs a
 * {@code org.springframework.security.core.userdetails.User} principal, whereas this codebase
 * expects {@code CustomUserPrincipal}. {@code CurrentUserService.getCurrentUser()} does an
 * {@code instanceof CustomUserPrincipal} check and returns {@code null} for anything else — so
 * {@code @WithMockUser} would silently produce a null user id and every ownership-scoped test
 * would exercise the wrong path.
 *
 * <p>Use {@code userId = ""} to model a token with no subject claim.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockPhotoAppUserSecurityContextFactory.class)
public @interface WithMockPhotoAppUser {

    /** JWT subject. Empty string means "no subject claim" and yields a null user id. */
    String userId() default "1";

    String username() default "admin";

    /** Roles, with or without the ROLE_ prefix. */
    String[] roles() default {"ROLE_ADMIN"};
}
