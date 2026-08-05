package com.photoapp.test.support.security;

import com.photoapp.security.provider.JwtTokenProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/*
    Mints JWTs for tests using the PRODUCTION JwtTokenProvider, not a hand-rolled signer.

    That choice is deliberate and was learned the hard way: an attempt to forge a token with
    openssl failed against six different guesses at how the configured secret is decoded,
    because jjwt's Base64 decoder handles the padding inside
    "3q2+7w==Q29uU2VjdXJlS2V5..." differently from GNU base64. Going through the real provider
    means a test token is produced exactly the way the application produces one, so a test can
    never pass against a token the application would reject.

    The Clock seam added on 2026-08-05 is what makes expiry testable without sleeping:
    expiredToken() mints at a clock set in the past.
 */
public final class TestJwt {

    /** The dev/test signing secret, matching photoapp.jwt.secret in the config repo. */
    public static final String SECRET =
            "3q2+7w==Q29uU2VjdXJlS2V5U2FtcGxlMTIzNDU2Nzg5MDEyMzQ1Njc4OQ==";

    /** 24h, matching photoapp.jwt.validity in the config repo. */
    public static final long VALIDITY_MILLIS = 86_400_000L;

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";

    private TestJwt() {
    }

    private static JwtTokenProvider provider(Clock clock) {
        return new JwtTokenProvider(SECRET, VALIDITY_MILLIS, clock);
    }

    /** A normal, currently-valid token. */
    public static String token(String userId, String username, String... roles) {
        return provider(Clock.systemUTC()).generateToken(userId, username, List.of(roles));
    }

    public static String adminToken() {
        return token("1", "admin", ROLE_ADMIN);
    }

    public static String userToken() {
        return token("2", "user1", ROLE_USER);
    }

    /** Both roles - the combination that hid the Step 8 authorization defect for months. */
    public static String adminAndUserToken() {
        return token("1", "admin", ROLE_ADMIN, ROLE_USER);
    }

    /**
     * A token that expired {@code ago} in the past. No sleeping: the provider is handed a
     * clock rewound far enough that the token's own validity window has already closed.
     */
    public static String expiredToken(Duration ago, String userId, String username, String... roles) {
        Instant issuedAt = Instant.now().minus(ago).minusMillis(VALIDITY_MILLIS);
        Clock rewound = Clock.fixed(issuedAt, ZoneOffset.UTC);
        return provider(rewound).generateToken(userId, username, List.of(roles));
    }

    public static String expiredAdminToken() {
        return expiredToken(Duration.ofMinutes(1), "1", "admin", ROLE_ADMIN);
    }

    /**
     * Signature-valid but carrying NO subject claim, so the principal has a null user id.
     * This is the shape that reached AccountServiceImpl.findAll and produced a 500 before
     * the 2026-08-05 fix; keep it available so Phase 3 can assert the 401.
     */
    public static String tokenWithoutSubject(String username, String... roles) {
        return provider(Clock.systemUTC()).generateToken(null, username, List.of(roles));
    }

    /** Signature-valid but with a non-numeric subject - the other half of that defect. */
    public static String tokenWithNonNumericSubject(String username, String... roles) {
        return provider(Clock.systemUTC()).generateToken("not-a-number", username, List.of(roles));
    }

    /** Structurally a JWT, but not signed with the configured secret. */
    public static String tokenWithWrongSignature() {
        String otherSecret = "b3RoZXJTZWNyZXRLZXlUaGF0SXNMb25nRW5vdWdoRm9ySFMyNTZBbGdvcml0aG0=";
        return new JwtTokenProvider(otherSecret, VALIDITY_MILLIS, Clock.systemUTC())
                .generateToken("1", "admin", List.of(ROLE_ADMIN));
    }

    /** Not a JWT at all. */
    public static String malformedToken() {
        return "not.a.jwt";
    }

    /** Convenience for MockMvc: the full header value. */
    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
