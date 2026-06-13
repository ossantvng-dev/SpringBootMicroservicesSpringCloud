package com.photoapp.auth.service;

import com.photoapp.entity.User;
import com.photoapp.feign.client.UserFeignClient;
import com.photoapp.security.model.CustomUserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserFeignClient userFeignClient;

    @Override
    public @NonNull UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
        log.info("AUTH SECURITY loadUserByUsername started username={}", username);
        User user = userFeignClient.findByUsernameAndActiveUser(username);
        if (user == null) {
            log.warn("AUTH SECURITY user not found or inactive username={}", username);
            throw new UsernameNotFoundException("User not found or inactive: " + username);
        }
        log.info("AUTH SECURITY user loaded userId={} username={}", user.getId(), username);
        CustomUserPrincipal principal = new CustomUserPrincipal(
                user.getId().toString(),
                user.getUsername(),
                user.getPasswordHash(),
                getAuthorities(user)
        );
        log.info("AUTH SECURITY principal created userId={} authorities={}", user.getId(), principal.getAuthorities().size());
        return principal;
    }

    private static @NonNull List<SimpleGrantedAuthority> getAuthorities(User user) {
        return user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName().name()))
                .collect(Collectors.toList());
    }
}
