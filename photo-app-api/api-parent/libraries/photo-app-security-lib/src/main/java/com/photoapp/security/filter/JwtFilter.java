package com.photoapp.security.filter;

import com.photoapp.security.model.CustomUserPrincipal;
import com.photoapp.security.parser.JwtClaimsParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;

@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    private final JwtClaimsParser jwtClaimsParser;

    public JwtFilter(JwtClaimsParser jwtClaimsParser) {
        this.jwtClaimsParser = jwtClaimsParser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                log.debug("Validating JWT for request path={}", request.getRequestURI());

                Collection<? extends GrantedAuthority> authorities =
                        jwtClaimsParser.getUserAuthorities(token);

                String userId = jwtClaimsParser.getJwtSubject(token);
                String username = jwtClaimsParser.getJwtUsername(token);

                CustomUserPrincipal principal =
                        new CustomUserPrincipal(userId, username, null, authorities);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.info("JWT validated successfully userId={} username={}", userId, username);

            /*
                Every failure below is handled the same way: leave the SecurityContext empty and
                let the chain continue. This filter's job is to establish an identity when one
                is presented, NOT to decide what an anonymous request is allowed to reach - that
                is authorizeHttpRequests' job, and it runs after this.

                Expiry used to be special-cased with sendError(401) + return, which short-circuited
                the chain before the authorization rules were ever consulted. Measured against the
                running stack on 2026-08-07: that made all three permitAll /auth endpoints answer
                401 whenever the caller happened to send a stale token. POST /auth/refresh with an
                expired header returned 401 while the same refresh token with no header returned
                200 a second later - and refresh is precisely the endpoint a client calls BECAUSE
                its access token expired, with the stale token still attached by its interceptor.
                A user whose session lapsed could not refresh and could not log back in.

                Protected paths are unaffected: no authentication in the context means
                authorizeHttpRequests denies, the authenticationEntryPoint answers 401, and the
                caller sees the same status as before - reached through the mechanism that is
                supposed to decide it.
             */
            } catch (io.jsonwebtoken.ExpiredJwtException e) {
                SecurityContextHolder.clearContext();
                log.warn("Expired JWT detected for request path={} message={}", request.getRequestURI(), e.getMessage());
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
                log.error("Invalid JWT for request path={} error={}", request.getRequestURI(), e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}

