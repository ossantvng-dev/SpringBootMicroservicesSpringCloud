package com.photoapp.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import javax.crypto.SecretKey;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class JwtClaimsParser {

    Jwt<?, ?> jwtObject;

    public JwtClaimsParser(String jwt, String secretToken) {
        this.jwtObject = parseJwt(jwt, secretToken);
    }

    @SuppressWarnings("unchecked")
    public Collection<? extends GrantedAuthority> getUserAuthorities() {
        Collection<String> scopes = ((Claims)jwtObject.getPayload()).get("scope", List.class);

        return scopes.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    public String getJwtSubject() {
        return ((Claims)jwtObject.getPayload()).getSubject();
    }

    private Jwt<?, ?> parseJwt(String jwt, String base64Secret) {
        byte[] secretKeyBytes = Decoders.BASE64.decode(base64Secret);
        SecretKey secretKey = Keys.hmacShaKeyFor(secretKeyBytes);
        JwtParser jwtParser = Jwts.parser().verifyWith(secretKey).build();
        return jwtParser.parse(jwt);
    }

}
