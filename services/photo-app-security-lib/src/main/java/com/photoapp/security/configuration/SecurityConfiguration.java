package com.photoapp.security.configuration;

import com.photoapp.security.filter.JwtFilter;
import com.photoapp.security.parser.JwtClaimsParser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    private final JwtClaimsParser jwtClaimsParser;

    public SecurityConfiguration(JwtClaimsParser jwtClaimsParser) {
        this.jwtClaimsParser = jwtClaimsParser;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                        //.requestMatchers("/users/*").permitAll()
                        //.requestMatchers("/users/username/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/users").permitAll() // user registry
                        // Protected Endpoints by Role
                        .requestMatchers("/users/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/accounts/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/albums/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/photos/**").hasAnyRole("USER","ADMIN")
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((_, response, _) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
                        )
                )
                .addFilterBefore(new JwtFilter(jwtClaimsParser), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

}
