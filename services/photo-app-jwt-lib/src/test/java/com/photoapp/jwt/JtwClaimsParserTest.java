package com.photoapp.jwt;


import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JtwClaimsParserTest {

    @Test
    void testParseTokenReturnsCorrectClaims() {
        // Base64-encoded secret key (must be >= 256 bits)
        String base64Secret = "MDEyMzQ1Njc4OUFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFla"; // example only

        // Generate token
        JwtTokenProvider provider = new JwtTokenProvider(base64Secret, 3600000);
        String token = provider.generateToken("user123", List.of("ROLE_USER"));

        // Parse token
        JwtClaimsParser parser = new JwtClaimsParser(token, base64Secret);

        // Verify subject
        assertEquals("user123", parser.getJwtSubject());

        // Verify authorities contain ROLE_USER
        var authorities = parser.getUserAuthorities();
        assertTrue(authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }
}

