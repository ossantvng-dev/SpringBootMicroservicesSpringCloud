package com.photoapp.feign.harness;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import org.junit.jupiter.api.function.ThrowingSupplier;

/**
 * One Feign method that carries {@code @CircuitBreaker} + {@code @Retry} + a fallback.
 *
 * <p>There are exactly twelve, and {@link com.photoapp.feign.FeignClientInventoryTest} proves it
 * by reflection so this catalogue cannot silently fall behind the interfaces. Holding them as data
 * is what lets the resilience matrix assert the same five properties against every one of them —
 * hand-writing sixty cases would guarantee the gap lands on the method nobody thought about.
 *
 * @param label      human-readable {@code Client#method}, used as the parameterized test name
 * @param breakerName the {@code @CircuitBreaker(name = …)}, i.e. the registry key
 * @param httpMethod  the downstream verb, for stubbing and for counting attempts
 * @param path        the downstream path, matched ignoring the query string
 * @param successBody the body a healthy downstream would return, or {@code null} for void methods
 * @param invoke      calls the method on a real Feign proxy, through the aspects
 */
public record ProtectedCall(
        String label,
        String breakerName,
        String httpMethod,
        String path,
        String successBody,
        ThrowingSupplier<Object> invoke) {

    public UrlPattern url() {
        return WireMock.urlPathEqualTo(path);
    }

    @Override
    public String toString() {
        return label;
    }
}
