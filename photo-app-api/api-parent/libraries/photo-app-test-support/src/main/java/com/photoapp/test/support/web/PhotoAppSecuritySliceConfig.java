package com.photoapp.test.support.web;

import com.photoapp.commons.exception.GlobalExceptionHandler;
import com.photoapp.security.configuration.SecurityConfiguration;
import com.photoapp.security.parser.JwtClaimsParser;
import com.photoapp.security.service.CurrentUserService;
import com.photoapp.test.support.security.TestJwt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;

/*
    Makes @WebMvcTest exercise the REAL security chain and the REAL exception advice.

    THIS IS LOAD-BEARING. photo-app-security-lib has no auto-configuration: applications pick
    the chain up with @ComponentScan("com.photoapp.security"), and @WebMvcTest does not
    component-scan. So a bare @WebMvcTest builds a DEFAULT Spring Security chain instead, and
    every authorization assertion would pass vacuously against rules that are not the ones
    shipped. The whole Phase 3 authorization matrix would be worthless and green.

    GlobalExceptionHandler is imported for the same reason: it lives in com.photoapp.commons,
    which a slice test also does not scan, and the ordering of its handlers is exactly what the
    Step 8 defect was about.

    Phase 1 ships a deliberate control test alongside this - see
    SecuritySliceControlTest in photo-app-commons - which fails if this configuration ever
    stops taking effect.
 */
@TestConfiguration
@Import({SecurityConfiguration.class, GlobalExceptionHandler.class, CurrentUserService.class})
public class PhotoAppSecuritySliceConfig {

    /**
     * The production JwtClaimsParser takes its secret from ${photoapp.jwt.secret}, which comes
     * from the Config Server at runtime. A slice test has no Config Server, so it is built here
     * with the same dev secret the fixtures sign with.
     */
    @Bean
    public JwtClaimsParser jwtClaimsParser(Clock clock) {
        return new JwtClaimsParser(TestJwt.SECRET, clock);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
