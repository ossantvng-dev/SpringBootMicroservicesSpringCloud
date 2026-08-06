package com.photoapp.security.model;

import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

public class CustomUserPrincipal implements UserDetails {

    @Getter
    private final String userId;
    private final String username;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomUserPrincipal(String userId,
                               String username,
                               String password,
                               Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.authorities = authorities;
    }

    @Override
    @NonNull
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /*
        @Nullable, matching UserDetails.getPassword() - the interface declares it nullable and
        this class must not narrow it. Two construction paths, and only one carries a password:
        CustomUserDetailsService supplies the real hash during login, while JwtFilter builds the
        principal from an already-verified token and passes null, because at that point there is
        no password to check and nothing asks for one.

        This annotation previously said @NonNull, which was a contract this class could not keep.
        Latent rather than live - nothing in the project calls getPassword() on a principal - but
        it advertised a guarantee that the JWT path breaks on every authenticated request.
     */
    @Override
    @Nullable
    public String getPassword() {
        return password;
    }

    @Override
    @NonNull
    public String getUsername() {
        return username;
    }

}
