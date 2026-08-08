package com.photoapp.feign;

import com.photoapp.feign.harness.FeignClientMetadata;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cloud.openfeign.FeignClient;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The completeness guard for Phase 4: proves the interfaces still match what the suites test.
 *
 * <p>Every other class in this phase is a matrix over a catalogue, and a matrix is only as good as
 * its catalogue. A thirteenth {@code @CircuitBreaker} method, or a fourth client, would simply not
 * appear in {@code FeignResilienceMatrixTest} — no failure, no warning, just an untested method.
 * This turns each of those into a build failure with a message saying what to add.
 *
 * <p>The counts asserted below are the reconciled Phase 4 inventory, confirmed against the code on
 * 2026-08-07: <strong>4 clients, 20 remote methods, 12 fallbacks.</strong>
 */
class FeignClientInventoryTest {

    static Stream<Class<?>> clients() {
        return FeignClientMetadata.CLIENTS.stream();
    }

    /**
     * Verifies the library still contains exactly the four clients the suites cover.
     *
     * <p>A fifth would be invisible: {@code FeignTestApplication} lists the clients explicitly, so a
     * new interface is not even instantiated, let alone tested. Scanning here for
     * {@code @FeignClient} independently of that list is what closes the loop.
     */
    @Test
    void thereAreExactlyFourFeignClients() {
        assertThat(FeignClientMetadata.CLIENTS)
                .as("a new @FeignClient interface must be added to FeignTestApplication, to the "
                        + "catalogue in FeignResilienceMatrixTest, and to a client suite of its own")
                .hasSize(4)
                .allSatisfy(c -> assertThat(c.isAnnotationPresent(FeignClient.class)).isTrue());
    }

    /**
     * Verifies the reconciled method and fallback counts still hold: 20 and 12.
     *
     * <p>These are the numbers the phase was scoped against. Asserting the total rather than each
     * client's individually is deliberate — the failure message should say the inventory moved,
     * which is the thing that needs a decision, not which file changed.
     */
    @Test
    @DisplayName("the inventory is still 20 remote methods and 12 fallbacks")
    void theInventoryStillMatchesTheReconciledCounts() {
        long remote = FeignClientMetadata.CLIENTS.stream()
                .mapToLong(c -> FeignClientMetadata.remoteMethods(c).size()).sum();
        long fallbacks = FeignClientMetadata.CLIENTS.stream()
                .mapToLong(c -> FeignClientMetadata.fallbackMethods(c).size()).sum();

        assertThat(remote)
                .as("Phase 4 was scoped against 20 remote methods; the inventory has moved")
                .isEqualTo(20);
        assertThat(fallbacks)
                .as("Phase 4 was scoped against 12 fallbacks; the inventory has moved")
                .isEqualTo(12);
    }

    /**
     * Verifies every {@code @CircuitBreaker} names a fallback method that actually exists, with the
     * signature Resilience4j requires.
     *
     * <p>This is the check with real teeth. {@code fallbackMethod} is a <em>string</em>, resolved at
     * runtime on the first failure — so a typo, a renamed method or a changed parameter list is
     * invisible until the downstream is already down, at which point Resilience4j throws
     * {@code NoSuchMethodException} instead of running the fallback, and an outage becomes a 500
     * with a confusing stack trace. Checking it by reflection moves that discovery to build time.
     *
     * <p>The required signature is the original method's parameters plus a trailing
     * {@code Throwable}, with a matching return type.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("clients")
    void everyCircuitBreakerNamesAFallbackThatExistsWithTheRightSignature(Class<?> client) {
        for (Method method : FeignClientMetadata.circuitBrokenMethods(client)) {
            String fallbackName = method.getAnnotation(CircuitBreaker.class).fallbackMethod();

            assertThat(fallbackName)
                    .as("%s declares @CircuitBreaker with no fallbackMethod", FeignClientMetadata.label(method))
                    .isNotBlank();

            Class<?>[] expected = new Class<?>[method.getParameterCount() + 1];
            System.arraycopy(method.getParameterTypes(), 0, expected, 0, method.getParameterCount());
            expected[method.getParameterCount()] = Throwable.class;

            Method fallback;
            try {
                fallback = client.getMethod(fallbackName, expected);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(
                        "%s names fallbackMethod \"%s\", which does not exist with the required "
                                + "signature (original parameters plus a trailing Throwable). "
                                + "Resilience4j resolves this by name at runtime, so this would "
                                + "only surface during a real outage."
                                .formatted(FeignClientMetadata.label(method), fallbackName), e);
            }

            assertThat(fallback.getReturnType())
                    .as("%s's fallback must return the same type as the method it stands in for",
                            FeignClientMetadata.label(method))
                    .isEqualTo(method.getReturnType());
            assertThat(fallback.isDefault())
                    .as("%s should be a default method on the interface, like the other eleven",
                            fallbackName)
                    .isTrue();
        }
    }

    /**
     * Verifies {@code @CircuitBreaker} and {@code @Retry} are always applied together, under the
     * same instance name.
     *
     * <p>They are separate annotations with separate registries, so it is entirely possible to add
     * one and forget the other — and each half is silently useless without the other. Retry without
     * a breaker hammers a service that is down; a breaker without retry opens on transient blips
     * that a single retry would have absorbed. Mismatched names are worse: both would work, against
     * two independently-configured instances, and nothing would say so.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("clients")
    void circuitBreakerAndRetryAreAlwaysPairedUnderTheSameName(Class<?> client) {
        for (Method method : FeignClientMetadata.remoteMethods(client)) {
            CircuitBreaker breaker = method.getAnnotation(CircuitBreaker.class);
            Retry retry = method.getAnnotation(Retry.class);

            if (breaker == null && retry == null) {
                continue;
            }

            assertThat(breaker)
                    .as("%s has @Retry but no @CircuitBreaker: it will keep retrying a downstream "
                            + "that is down, with nothing to shed the load",
                            FeignClientMetadata.label(method))
                    .isNotNull();
            assertThat(retry)
                    .as("%s has @CircuitBreaker but no @Retry: transient blips that one retry "
                            + "would absorb will count toward opening the circuit",
                            FeignClientMetadata.label(method))
                    .isNotNull();
            assertThat(retry.name())
                    .as("%s uses different instance names for its breaker and its retry, so the "
                            + "two are configured independently and nothing says so",
                            FeignClientMetadata.label(method))
                    .isEqualTo(breaker.name());
        }
    }

    /**
     * Verifies the twelve circuit-breaker instance names are exactly the ones the config repo
     * declares and the matrix tests drive.
     *
     * <p>The name is the join between three things that live apart: the annotation, the
     * {@code resilience4j.circuitbreaker.instances.*} entries in the external config repo, and the
     * catalogue in {@code FeignResilienceMatrixTest}. A rename in one place is silent in the other
     * two — Resilience4j happily creates an instance on demand with default config, so a typo
     * produces a working breaker that ignores every setting anyone deliberately chose for it.
     */
    @Test
    void theTwelveBreakerNamesAreTheExpectedOnes() {
        assertThat(FeignClientMetadata.allBreakerNames())
                .as("a renamed instance silently falls back to Resilience4j's own defaults rather "
                        + "than the config repo's, and nothing reports it")
                .containsExactly(
                        "photo-app-accounts-service-deleteByUserId",
                        "photo-app-accounts-service-findAll",
                        "photo-app-accounts-service-findById",
                        "photo-app-albums-service-countByAccountId",
                        "photo-app-albums-service-deleteByAccountIds",
                        "photo-app-albums-service-findAll",
                        "photo-app-albums-service-findById",
                        "photo-app-photos-service-countByAlbumIds",
                        "photo-app-photos-service-deleteByAlbumIds",
                        "photo-app-users-service-findById",
                        "photo-app-users-service-findByUsernameAndActiveUser",
                        "photo-app-users-service-isActive");
    }

    /**
     * Pins the eight remote methods that carry no resilience annotation at all.
     *
     * <p>Not an assertion that this is correct — it is an assertion that it is <em>known</em>. The
     * list is uneven in a way that looks accidental rather than designed: {@code findById} is
     * protected on the account and album clients but not on the photo client, and the three
     * {@code findAll} methods are protected on two clients and not the third. All eight are
     * currently uncalled, so nothing is broken today, and adding a caller is the moment the
     * inconsistency starts to matter.
     *
     * <p>Failing this test is the intended outcome of adding annotations to any of them: the change
     * is then deliberate, and the method has to be added to the matrix catalogue at the same time.
     */
    @Test
    @DisplayName("the eight unprotected methods are a known, deliberate list")
    void theUnprotectedMethodsAreKnown() {
        assertThat(FeignClientMetadata.unprotectedMethodLabels())
                .as("if a method gained @CircuitBreaker/@Retry, add it to the catalogue in "
                        + "FeignResilienceMatrixTest too — otherwise it is annotated but untested")
                .containsExactly(
                        "AccountFeignClient#activateOrDeactivate",
                        "AccountFeignClient#deleteById",
                        "AlbumFeignClient#activateOrDeactivate",
                        "AlbumFeignClient#deleteById",
                        "PhotoFeignClient#activateOrDeactivate",
                        "PhotoFeignClient#deleteById",
                        "PhotoFeignClient#findAll",
                        "PhotoFeignClient#findById");
    }

    /**
     * Verifies every client points at {@code FeignConfiguration}.
     *
     * <p>That configuration is what installs {@code CustomFeignErrorDecoder} and
     * {@code FeignAuthInterceptor} on the client's child context. A client that omitted it would get
     * Feign's default {@code ErrorDecoder} instead — meaning raw {@code FeignException}s rather than
     * {@code ApplicationException}s, which the predicate and the fallbacks both key off. Every
     * resilience behaviour in this phase would change for that one client, silently.
     */
    @Test
    void everyClientUsesTheSharedFeignConfiguration() {
        for (Class<?> client : FeignClientMetadata.CLIENTS) {
            FeignClient annotation = client.getAnnotation(FeignClient.class);

            assertThat(List.of(annotation.configuration()))
                    .as("%s does not use FeignConfiguration, so it gets Feign's default "
                            + "ErrorDecoder — no ApplicationException, and the predicate and "
                            + "fallbacks stop working for it", client.getSimpleName())
                    .contains(com.photoapp.feign.configuration.FeignConfiguration.class);
            assertThat(annotation.name())
                    .as("%s must target a service id, since resolution goes through the load "
                            + "balancer", client.getSimpleName())
                    .startsWith("photo-app-");
        }
    }
}
