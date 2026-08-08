package com.photoapp.feign.harness;

import com.photoapp.feign.client.AccountFeignClient;
import com.photoapp.feign.client.AlbumFeignClient;
import com.photoapp.feign.client.PhotoFeignClient;
import com.photoapp.feign.client.UserFeignClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the four client interfaces by reflection, so the test suites can be checked against the
 * code rather than against a list somebody maintained by hand.
 *
 * <p>Phase 3 established the pattern: a matrix test is only as complete as its catalogue, and a
 * catalogue that drifts from the interfaces fails open — the untested method is simply absent, and
 * absence is invisible in a green build.
 */
public final class FeignClientMetadata {

    private FeignClientMetadata() {
    }

    /** The four {@code @FeignClient} interfaces in this library. */
    public static final List<Class<?>> CLIENTS = List.of(
            UserFeignClient.class,
            AccountFeignClient.class,
            AlbumFeignClient.class,
            PhotoFeignClient.class);

    /** The remote methods — abstract, i.e. everything that is not a {@code default} fallback. */
    public static List<Method> remoteMethods(Class<?> client) {
        return Arrays.stream(client.getDeclaredMethods())
                .filter(m -> !m.isDefault())
                .filter(m -> !m.isSynthetic())
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /** The {@code default} fallback methods declared on the interface. */
    public static List<Method> fallbackMethods(Class<?> client) {
        return Arrays.stream(client.getDeclaredMethods())
                .filter(Method::isDefault)
                .filter(m -> !m.isSynthetic())
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /** Remote methods carrying {@code @CircuitBreaker}. */
    public static List<Method> circuitBrokenMethods(Class<?> client) {
        return remoteMethods(client).stream()
                .filter(m -> m.isAnnotationPresent(CircuitBreaker.class))
                .toList();
    }

    /** Every {@code @CircuitBreaker(name = …)} across all four clients, sorted. */
    public static List<String> allBreakerNames() {
        return CLIENTS.stream()
                .flatMap(c -> circuitBrokenMethods(c).stream())
                .map(m -> m.getAnnotation(CircuitBreaker.class).name())
                .sorted()
                .toList();
    }

    /** {@code Client#method} for a method, as the suites label it. */
    public static String label(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    /** Remote methods carrying no resilience annotation at all. */
    public static List<String> unprotectedMethodLabels() {
        return CLIENTS.stream()
                .flatMap(c -> remoteMethods(c).stream())
                .filter(m -> !m.isAnnotationPresent(CircuitBreaker.class))
                .filter(m -> !m.isAnnotationPresent(Retry.class))
                .map(FeignClientMetadata::label)
                .sorted()
                .toList();
    }
}
