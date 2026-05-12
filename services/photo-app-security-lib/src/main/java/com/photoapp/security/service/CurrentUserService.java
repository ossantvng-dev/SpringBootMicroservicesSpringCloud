package com.photoapp.security.service;

import com.photoapp.security.model.CustomUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

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
}
