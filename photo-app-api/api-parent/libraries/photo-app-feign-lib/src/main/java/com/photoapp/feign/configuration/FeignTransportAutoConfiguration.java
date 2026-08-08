package com.photoapp.feign.configuration;

import feign.hc5.ApacheHttp5Client;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration;
import org.springframework.context.annotation.Bean;

/*
    Makes Resilience4j the ONLY thing that retries.

    Both Feign transports retry underneath it, silently:

      feign.Client$Default (java.net.HttpURLConnection)
          retries an idempotent request once when the connection breaks mid-flight.
          Measured 2026-08-07: one logical call against an unreachable downstream
          delivered SIX requests, while @Retry(maxAttempts=3) and every metric said
          three.

      Apache HttpClient 5 (feign-hc5)
          DefaultHttpRequestRetryStrategy, maxRetries=1, and it ALSO retries the
          response codes 429 and 503. Measured on the same day, immediately after
          adding feign-hc5: the transport failure was still doubled to six, and a
          downstream 503 on a method with no @Retry at all was sent twice.

    So switching transport did not fix the amplification - it moved it, and widened
    it to cover 503 responses that the old transport passed straight through.

    Two layers of retry that do not know about each other is the problem, not the
    number either one chooses. The config repo sets maxAttempts=3 with a 2s->4s
    backoff, DownstreamFailurePredicate decides what is worth retrying, and the
    circuit breaker counts what happens - none of which the transport participates
    in. Its retries are invisible to all three: they do not appear in Resilience4j's
    metrics, they ignore the predicate, and they land on exactly the downstream that
    is already failing.

    Disabling them leaves one retry authority, configured in one place, observable
    in the actuator. RetryBehaviourTest asserts the resulting counts.
 */
@AutoConfiguration(before = org.springframework.cloud.openfeign.FeignAutoConfiguration.class)
@ConditionalOnClass(ApacheHttp5Client.class)
public class FeignTransportAutoConfiguration {

    /**
     * Turns off HttpClient 5's own retry strategy.
     *
     * <p>{@code HttpClientBuilderCustomizer} is Spring Cloud OpenFeign's supported hook into the
     * {@code CloseableHttpClient} it builds, so this adds a bean rather than replacing the
     * client and inheriting responsibility for the rest of its configuration.
     */
    @Bean
    public HttpClient5FeignConfiguration.HttpClientBuilderCustomizer noTransportLevelRetries() {
        return HttpClientBuilder -> HttpClientBuilder.disableAutomaticRetries();
    }
}
