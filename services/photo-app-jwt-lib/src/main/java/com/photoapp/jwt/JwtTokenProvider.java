package com.photoapp.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;

public class JwtTokenProvider {
    private final SecretKey secretKey;
    private final long validityInMillis;

    public JwtTokenProvider(String base64Secret, long validityInMillis) {
        byte[] secretKeyBytes = Decoders.BASE64.decode(base64Secret);
        this.secretKey = Keys.hmacShaKeyFor(secretKeyBytes);
        this.validityInMillis = validityInMillis;
    }

    public String generateToken(String subject, List<String> scopes) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + validityInMillis);

        return Jwts.builder()
                .subject(subject)
                .claim("scope", scopes)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }
}
