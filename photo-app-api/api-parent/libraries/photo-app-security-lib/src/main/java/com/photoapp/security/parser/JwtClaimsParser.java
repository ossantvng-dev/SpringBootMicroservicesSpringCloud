package com.photoapp.security.parser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtClaimsParser {

    private final SecretKey secretKey;
    private final Clock clock;

    public JwtClaimsParser(@Value("${photoapp.jwt.secret}") String base64Secret, Clock clock) {
        byte[] secretKeyBytes = Decoders.BASE64.decode(base64Secret);
        this.secretKey = Keys.hmacShaKeyFor(secretKeyBytes);
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public Collection<? extends GrantedAuthority> getUserAuthorities(String jwt) {
        Claims claims = parseClaims(jwt);
        Collection<String> scopes = claims.get("scope", List.class);

        return scopes.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

    }

    public String getJwtSubject(String jwt) {
        Claims claims = parseClaims(jwt);
        return claims.getSubject();
    }

    public String getJwtUsername(String jwt) {
        Claims claims = parseClaims(jwt);
        return claims.get("username", String.class);
    }

    private Claims parseClaims(String jwt) {
        JwtParser jwtParser = Jwts.parser()
                .verifyWith(secretKey)
                .build();

        Jwt<?, ?> parsedJwt = jwtParser.parse(jwt.replace("Bearer ", ""));
        Claims claims = (Claims) parsedJwt.getPayload();

        // Validate token expiration. Date.from(clock.instant()) is the same epoch
        // millisecond new Date() produced, so the comparison is unchanged.
        Date expiration = claims.getExpiration();
        if (expiration != null && expiration.before(Date.from(clock.instant()))) {
            throw new io.jsonwebtoken.ExpiredJwtException(null, claims, "Token expired");
        }

        return claims;
    }

}
