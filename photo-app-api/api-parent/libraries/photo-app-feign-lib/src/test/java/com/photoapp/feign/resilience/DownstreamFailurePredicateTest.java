package com.photoapp.feign.resilience;

import com.photoapp.commons.exception.ApplicationException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DownstreamFailurePredicate}, the class the 2026-08-05 fix added.
 *
 * <p>{@link FeignResilienceMatrixTest} proves the predicate's effect through the whole stack, which
 * is the assertion that matters most. These tests do something the matrix cannot: they cover the
 * inputs the stack never produces today but would after any change to the decoder — a raw
 * {@code IOException}, a wrapped cause chain, a 4xx nested three levels deep. Those are the cases
 * where a rewrite would break the predicate silently, because no existing integration test would
 * exercise them.
 *
 * <p>The predicate answers one question: <em>is this a failure of the downstream service?</em>
 * Wired as both {@code recordFailurePredicate} and {@code retryExceptionPredicate}, so a wrong
 * answer either opens circuits on ordinary user error (the original defect) or stops opening them
 * during a real outage (the risk of over-correcting).
 */
class DownstreamFailurePredicateTest {

    private final DownstreamFailurePredicate predicate = new DownstreamFailurePredicate();

    /**
     * Verifies no 4xx counts as a failure of the callee, across the whole range.
     *
     * <p>Parameterised over more than the three statuses the decoder produces today, because the
     * predicate is written against {@code is4xxClientError()} rather than against a status list —
     * so the contract really is "any 4xx", and a future decoder branch for 409 or 429 must inherit
     * it without anyone remembering to update this class.
     *
     * <p>The list stops at 451 rather than 499 because {@code HttpStatus.valueOf} rejects
     * unregistered codes — the same constraint that makes
     * {@code CustomFeignErrorDecoderTest#anUnrecognisedStatusCodeThrowsOutOfTheDecoder} a
     * characterization test rather than an ordinary one.
     */
    @ParameterizedTest(name = "{0} is not a downstream failure")
    @ValueSource(ints = {400, 401, 403, 404, 409, 422, 429, 451})
    void aDownstreamClientErrorIsNotAFailureOfTheDownstream(int status) {
        ApplicationException clientError =
                new ApplicationException("rejected", HttpStatus.valueOf(status));

        assertThat(predicate.test(clientError))
                .as("%d means the callee answered correctly and said no. Counting it opens the "
                        + "circuit on ordinary user error — the 2026-08-05 defect.", status)
                .isFalse();
    }

    /** Verifies a 5xx carried on an ApplicationException does count — the callee really failed. */
    @ParameterizedTest(name = "{0} is a downstream failure")
    @ValueSource(ints = {500, 502, 503, 504})
    void aDownstreamServerErrorIsAFailure(int status) {
        assertThat(predicate.test(new ApplicationException("boom", HttpStatus.valueOf(status))))
                .isTrue();
    }

    /**
     * Verifies transport-level failures count, since they carry no status at all.
     *
     * <p>This is the predicate's default branch and the single most important thing it must get
     * right: an unreachable service is the textbook case for a circuit breaker. If this ever
     * returned false, the breaker would stop opening during exactly the outage it exists for, and
     * every other test in this class would still pass.
     */
    @Test
    void transportFailuresCountBecauseTheyCarryNoDownstreamStatus() {
        assertThat(predicate.test(new ConnectException("connection refused"))).isTrue();
        assertThat(predicate.test(new SocketTimeoutException("read timed out"))).isTrue();
        assertThat(predicate.test(new UnknownHostException("photo-app-users-service"))).isTrue();
        assertThat(predicate.test(new IOException("broken pipe"))).isTrue();
    }

    /**
     * Verifies the predicate walks the cause chain rather than looking only at the top exception.
     *
     * <p>The reason the loop exists. Resilience4j's aspects and Feign both wrap exceptions on the
     * way out, so the {@code ApplicationException} carrying the real status is rarely the outermost
     * one. A predicate that only inspected the top level would see a {@code RuntimeException},
     * fall to the default branch and count every 404 as an outage — which is the original defect,
     * reintroduced through a different door.
     */
    @Test
    void aWrapped4xxIsStillFoundThroughTheCauseChain() {
        Throwable nested = new RuntimeException("aspect wrapper",
                new IllegalStateException("feign wrapper",
                        new ApplicationException("no such user", HttpStatus.NOT_FOUND)));

        assertThat(predicate.test(nested))
                .as("the status-carrying exception is almost never the outermost one")
                .isFalse();
    }

    /** Verifies a wrapped 5xx is likewise found, so wrapping does not flip the answer either way. */
    @Test
    void aWrapped5xxIsStillCounted() {
        Throwable nested = new RuntimeException("wrapper",
                new ApplicationException("downstream exploded", HttpStatus.BAD_GATEWAY));

        assertThat(predicate.test(nested)).isTrue();
    }

    /**
     * Verifies the first {@code ApplicationException} in the chain wins, not the innermost.
     *
     * <p>Worth pinning because the loop returns on the first match, so a chain carrying both a 503
     * and a 404 resolves to whichever is nearer the surface. That is the right call — the outer
     * one is the more recent, better-informed judgement — but it is a decision the code makes
     * implicitly and a rewrite could invert without any other test noticing.
     */
    @Test
    void theOutermostApplicationExceptionDecides() {
        Throwable outer5xxOverInner4xx = new ApplicationException("gateway failed", HttpStatus.BAD_GATEWAY);
        outer5xxOverInner4xx.initCause(new ApplicationException("not found", HttpStatus.NOT_FOUND));

        assertThat(predicate.test(outer5xxOverInner4xx))
                .as("the outer, more recent judgement wins")
                .isTrue();
    }

    /**
     * Verifies an already-open circuit's rejection counts as a failure.
     *
     * <p>{@code CallNotPermittedException} carries no downstream status, so it lands on the default
     * branch. That is what keeps a breaker open while the downstream is still down instead of
     * letting the rejections themselves look like healthy calls — the pathological alternative
     * being a breaker that closes because it stopped making requests.
     */
    @Test
    void anOpenCircuitsOwnRejectionCounts() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("probe");
        breaker.transitionToOpenState();

        Throwable rejected = CallNotPermittedException.createCallNotPermittedException(breaker);

        assertThat(predicate.test(rejected)).isTrue();
    }

    /**
     * Verifies a null throwable is treated as a failure rather than throwing.
     *
     * <p>Not reachable from Resilience4j today, but the loop's termination condition is
     * {@code current != null} and this is the edge that would turn a predicate call into a
     * {@code NullPointerException} inside the circuit-breaker aspect — a failure in the failure
     * handler, which is the worst place to have one.
     */
    @Test
    void aNullThrowableDoesNotBlowUpThePredicate() {
        assertThat(predicate.test(null)).isTrue();
    }
}
