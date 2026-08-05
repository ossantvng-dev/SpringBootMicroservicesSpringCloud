package com.photoapp.security.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@Configuration
public class SecurityBeans {

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /*
        The production time source. Behaviourally identical to what the code did before:
        Clock.systemUTC().millis() is specified as equivalent to System.currentTimeMillis(),
        and Date.from(Clock.systemUTC().instant()) yields the same epoch milliseconds as
        new Date(). This exists purely as a seam - a test can supply Clock.fixed(...) and
        assert token issuance and expiry without sleeping.

        Declared here because JwtTokenProvider and JwtClaimsParser live in this library and
        are component-scanned by the gateway and all five services, so every context that
        has them also needs this bean. @ConditionalOnMissingBean lets any consumer - or a
        test slice - replace it without touching this class.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnMissingBean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("JWT only");
        };
    }

}
