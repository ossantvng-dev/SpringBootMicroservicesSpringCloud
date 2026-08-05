package com.photoapp.test.support.security;

import com.photoapp.security.model.CustomUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;

/*
    Builders for the principal JwtFilter puts in the SecurityContext.

    Service-layer tests need these because CurrentUserService reads SecurityContextHolder
    statically - there is no seam to inject a user, so the context has to be populated for
    real. Prefer @WithMockPhotoAppUser on MockMvc tests; use these directly in plain unit
    tests of a service class.
 */
public final class TestPrincipals {

    private TestPrincipals() {
    }

    public static CustomUserPrincipal principal(String userId, String username, String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        return new CustomUserPrincipal(userId, username, null, authorities);
    }

    public static CustomUserPrincipal admin() {
        return principal("1", "admin", TestJwt.ROLE_ADMIN);
    }

    public static CustomUserPrincipal user() {
        return principal("2", "user1", TestJwt.ROLE_USER);
    }

    /**
     * A principal with a null user id - what a signature-valid token carrying no "sub" claim
     * produces. Reaching this state used to be a 500; it is now a 401.
     */
    public static CustomUserPrincipal withoutUserId() {
        return principal(null, "ghost", TestJwt.ROLE_USER);
    }

    public static CustomUserPrincipal withNonNumericUserId() {
        return principal("not-a-number", "ghost", TestJwt.ROLE_USER);
    }

    public static Authentication authentication(CustomUserPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
