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
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtClaimsParser {

    private final SecretKey secretKey;

    public JwtClaimsParser(@Value("${photoapp.jwt.secret}") String base64Secret) {
        byte[] secretKeyBytes = Decoders.BASE64.decode(base64Secret);
        this.secretKey = Keys.hmacShaKeyFor(secretKeyBytes);
    }

    @SuppressWarnings("unchecked")
    public Collection<? extends GrantedAuthority> getUserAuthorities(String jwt) {
        Claims claims = parseClaims(jwt);
        Collection<String> scopes = claims.get("scope", List.class);

        return scopes.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    public String getJwtSubject(String jwt) {
        Claims claims = parseClaims(jwt);
        return claims.getSubject();
    }

    private Claims parseClaims(String jwt) {
        JwtParser jwtParser = Jwts.parser()
                .verifyWith(secretKey)
                .build();
        Jwt<?, ?> parsedJwt = jwtParser.parse(jwt.replace("Bearer ", ""));
        return (Claims) parsedJwt.getPayload();
    }
}
