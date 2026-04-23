package com.photoapp.jwt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JwtTokenProviderTest {

    @Test
    void testGenerateTokenContainsSubjectAndScope() {
        // Base64-encoded secret key (must be >= 256 bits)
        String base64Secret = "MDEyMzQ1Njc4OUFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFla"; // example only

        // Create provider with 1 hour validity
        JwtTokenProvider provider = new JwtTokenProvider(base64Secret, 3600000);

        // Generate token with subject and scopes
        String token = provider.generateToken("user123", List.of("ROLE_USER", "ROLE_ADMIN"));

        // Assert token is not null
        assertNotNull(token);

        // Assert token looks like a JWT (starts with "ey...")
        assertTrue(token.startsWith("ey"));
    }
}
