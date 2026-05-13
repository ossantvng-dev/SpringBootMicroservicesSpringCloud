package com.photoapp.auth.service;

import com.photoapp.entity.User;
import com.photoapp.feign.UserFeignClient;
import com.photoapp.security.model.CustomUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserFeignClient userFeignClient;

    @Override
    public @NonNull UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
        User user = userFeignClient.findByUsernameAndActiveUser(username);
        if (user == null) {
            throw new UsernameNotFoundException("User not found or inactive: " + username);
        }
        return new CustomUserPrincipal(
                user.getId().toString(),
                user.getUsername(),
                user.getPasswordHash(),
                getAuthorities(user)
        );
    }

    private static @NonNull List<SimpleGrantedAuthority> getAuthorities(User user) {
        return user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName().name()))
                .collect(Collectors.toList());
    }
}
