package com.photoapp.security.provider;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;

    private final long validityInMillis;

    public JwtTokenProvider(@Value("${photoapp.jwt.secret}") String base64Secret,
                            @Value("${photoapp.jwt.validity}") long validityInMillis) {
        byte[] secretKeyBytes = Decoders.BASE64.decode(base64Secret);
        this.secretKey = Keys.hmacShaKeyFor(secretKeyBytes);
        this.validityInMillis = validityInMillis;
    }

    public long getValidityInMillis() { return validityInMillis; }

    public String generateToken(String userId, String username, List<String> scopes) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + validityInMillis);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("scope", scopes)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }
}
