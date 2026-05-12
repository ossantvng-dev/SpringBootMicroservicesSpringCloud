package com.photoapp.security.configuration;

import com.photoapp.security.filter.JwtFilter;
import com.photoapp.security.parser.JwtClaimsParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtClaimsParser jwtClaimsParser) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny) // clickjacking protection
                        .contentSecurityPolicy(csp -> csp.policyDirectives("script-src 'self'")) // protección XSS
                )
                .sessionManagement(session -> session.sessionFixation().migrateSession()) // Session Fixation
                .authorizeHttpRequests(auth -> auth
                        // Public Endpoints
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/users").permitAll() // registro de usuario

                        // Protected Endpoints by Role
                        .requestMatchers("/users/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/accounts/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/albums/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/photos/**").hasAnyRole("USER","ADMIN")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new JwtFilter(jwtClaimsParser), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

}
