package com.photoapp.security.service;

import com.photoapp.security.model.CustomUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

@Service
public class CurrentUserService {

    public CustomUserPrincipal getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserPrincipal principal) {
            return principal;
        }
        return null;
    }

    public String getCurrentUserId() {
        CustomUserPrincipal principal = getCurrentUser();
        return principal != null ? principal.getUserId() : null;
    }

    public String getCurrentUsername() {
        CustomUserPrincipal principal = getCurrentUser();
        return principal != null ? principal.getUsername() : null;
    }

    public Collection<? extends GrantedAuthority> getCurrentUserAuthorities() {
        CustomUserPrincipal principal = getCurrentUser();
        return principal != null ? principal.getAuthorities() : Collections.emptyList();
    }

    public boolean isAdmin() {
        return getCurrentUserAuthorities().stream()
                .anyMatch(a -> Objects.equals(a.getAuthority(), "ROLE_ADMIN"));
    }

    public boolean isUser() {
        return getCurrentUserAuthorities().stream()
                .anyMatch(a -> Objects.equals(a.getAuthority(), "ROLE_USER"));
    }

    public boolean canAccessResource(String resourceOwnerId) {
        String currentUserId = getCurrentUserId();
        if (isAdmin()) {
            return true;
        }
        return Objects.equals(resourceOwnerId, currentUserId);
    }



}
