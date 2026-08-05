package com.photoapp.security.provider;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.util.Date;
import java.util.List;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final Clock clock;

    @Getter
    private final long validityInMillis;

    public JwtTokenProvider(@Value("${photoapp.jwt.secret}") String base64Secret,
                            @Value("${photoapp.jwt.validity}") long validityInMillis,
                            Clock clock) {
        byte[] secretKeyBytes = Decoders.BASE64.decode(base64Secret);
        this.secretKey = Keys.hmacShaKeyFor(secretKeyBytes);
        this.validityInMillis = validityInMillis;
        this.clock = clock;
    }

    public String generateToken(String userId, String username, List<String> scopes) {
        // Date.from(Clock.systemUTC().instant()) is the same epoch millisecond as new Date().
        // JJWT serialises iat/exp as epoch SECONDS, so even the sub-millisecond difference
        // between Instant and Date cannot change the emitted token.
        Date now = Date.from(clock.instant());
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
