package com.photoapp.security.configuration;

import com.photoapp.security.filter.ReactiveJwtFilter;
import com.photoapp.security.parser.JwtClaimsParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ReactiveSecurityConfiguration {

    private final JwtClaimsParser jwtClaimsParser;

    public ReactiveSecurityConfiguration(JwtClaimsParser jwtClaimsParser) {
        this.jwtClaimsParser = jwtClaimsParser;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Public Endpoints
                        //.pathMatchers("/users/*").permitAll()   // exposes /users/{id}
                        .pathMatchers("/auth/**").permitAll()
                        .pathMatchers("/users/username/**").permitAll()
                        .pathMatchers(HttpMethod.POST,"/users").permitAll()
                        // ROLE Protection
                        .pathMatchers("/users/**").hasAnyRole("USER", "ADMIN")
                        .pathMatchers("/accounts/**").hasAnyRole("USER", "ADMIN")
                        .pathMatchers("/albums/**").hasAnyRole("USER", "ADMIN")
                        .pathMatchers("/photos/**").hasAnyRole("USER", "ADMIN")
                        // Everything else requires authentication
                        .anyExchange().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, _) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        })
                        .accessDeniedHandler((exchange, _) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        })
                )
                // Reactive JWT Filter registry
                .addFilterAt(new ReactiveJwtFilter(jwtClaimsParser), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
