package com.photoapp.config.server.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/*
    HTTP Basic instead of the JWT chain in photo-app-security-lib. That library targets
    human end-users authenticating through the gateway; this server's clients are other
    Spring applications reading their configuration at startup, which is a different
    consumer model that Basic fits correctly.

    Credentials come from CONFIG_SERVER_ADMIN_USER / CONFIG_SERVER_ADMIN_PASSWORD, bound
    through spring.security.user.* in application.properties.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Machine-to-machine: no browser, no session, so no CSRF token to carry
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Left open so container healthchecks work without credentials
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Covers /encrypt/**, /decrypt/**, /{app}/{profile} and /actuator/busrefresh
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

}
