package com.photoapp.security.filter;

import com.photoapp.security.model.CustomUserPrincipal;
import com.photoapp.security.parser.JwtClaimsParser;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collection;

@Slf4j
public class ReactiveJwtFilter implements WebFilter {

    private final JwtClaimsParser jwtClaimsParser;

    public ReactiveJwtFilter(JwtClaimsParser jwtClaimsParser) {
        this.jwtClaimsParser = jwtClaimsParser;
    }

    @Override
    public @NonNull Mono<Void> filter(ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                log.debug("Validating JWT for reactive request path={}", exchange.getRequest().getURI());

                Collection<? extends GrantedAuthority> authorities =
                        jwtClaimsParser.getUserAuthorities(token);

                String userId = jwtClaimsParser.getJwtSubject(token);
                String username = jwtClaimsParser.getJwtUsername(token);

                CustomUserPrincipal principal =
                        new CustomUserPrincipal(userId, username, null, authorities);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);

                log.info("JWT validated successfully userId={} username={}", userId, username);

                return chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
            } catch (io.jsonwebtoken.ExpiredJwtException e) {
                log.warn("Expired JWT detected for reactive request path={} message={}", exchange.getRequest().getURI(), e.getMessage());
                return chain.filter(exchange);
            } catch (Exception e) {
                log.error("Invalid JWT for reactive request path={} error={}", exchange.getRequest().getURI(), e.getMessage());
                return chain.filter(exchange);
            }
        }
        return chain.filter(exchange);
    }
}
