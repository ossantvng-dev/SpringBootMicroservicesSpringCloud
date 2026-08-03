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

/*
    Single security chain for every component in the ecosystem, gateway included.
    The gateway runs on the servlet stack (spring-cloud-gateway-server-webmvc), so there is
    no reactive counterpart to keep in sync.
 */
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
                        .requestMatchers("/users/username/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/users").permitAll() // user registry
                        .requestMatchers("/actuator/**").permitAll()
                        // Servlet ERROR dispatch: without this every downstream failure
                        // is re-authorized as /error and masked as a 401
                        .requestMatchers("/error").permitAll()
                        /*
                            OpenAPI, anonymous like /actuator/health. These must stay above the
                            role rules below: request matchers are evaluated in declaration order.
                            /api-docs/** is the gateway-side aggregation prefix; the specs it
                            proxies are served from /v3/api-docs on each business service.
                            Note this publishes the endpoint inventory (not the data) to anyone
                            who can reach the port - acceptable while the stack is local-only.
                         */
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api-docs/**").permitAll()
                        // Protected Endpoints by Role
                        .requestMatchers("/users/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/accounts/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/albums/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/photos/**").hasAnyRole("USER","ADMIN")
                        .requestMatchers("/encrypt/**").hasRole("ADMIN")
                        .requestMatchers("/decrypt/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/busrefresh").hasRole("ADMIN")
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                /*
                    authenticationEntryPoint:
                        It is executed when an unauthenticated user attempts to access a resource that requires authentication

                    accessDeniedHandler:
                        It is executed when a user is authenticated, but does not have sufficient permissions.
                */
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((_, response, _) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
                        )
                        .accessDeniedHandler((_, response, _) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")
                        )
                )
                .addFilterBefore(new JwtFilter(jwtClaimsParser), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

}
