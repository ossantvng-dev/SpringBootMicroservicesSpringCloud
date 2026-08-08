package com.photoapp.feign.resilience;

import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.harness.AbstractFeignClientTest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The circuit breaker state machine: CLOSED → OPEN → HALF_OPEN → CLOSED, and the reopen path.
 *
 * <p>Driven against the {@code test-transition} instance from the registry rather than through a
 * Feign call, which is the approach testing-plan.md's open question 3 resolved on. Production's
 * {@code minimumNumberOfCalls=5} and {@code waitDurationInOpenState=10s}, plus a 2s→4s retry
 * backoff, make one full cycle take tens of seconds through a real client; that is not a test, it
 * is a wait. Driving the breaker directly also removes the retry aspect from the picture entirely,
 * so a transition assertion cannot be confused by an attempt count.
 *
 * <p>What the real instances do is asserted separately and behaviourally in
 * {@link FeignResilienceMatrixTest} — that suite proves the twelve breakers are wired to the right
 * methods and open on real failures; this one proves the machine those breakers run is correct.
 * Neither substitutes for the other.
 */
class CircuitBreakerStateTransitionTest extends AbstractFeignClientTest {

    private static final String INSTANCE = "test-transition";

    /** A failure that {@link DownstreamFailurePredicate} counts: no downstream 4xx in the chain. */
    private static void fail() {
        throw new IllegalStateException("downstream is down");
    }

    private void drive(CircuitBreaker breaker, int failures, int successes) {
        for (int i = 0; i < failures; i++) {
            catchThrowable(() -> breaker.executeRunnable(CircuitBreakerStateTransitionTest::fail));
        }
        for (int i = 0; i < successes; i++) {
            breaker.executeRunnable(() -> {
            });
        }
    }

    /**
     * Verifies the test-only instance mirrors production's <em>semantics</em>, differing only in
     * the numbers that make a test slow.
     *
     * <p>Without this, every transition asserted below could be true of a state machine configured
     * nothing like the one that ships, and the suite would be measuring a fiction. Window type and
     * threshold are the two settings that change what "failing" means — a TIME_BASED window with the
     * same threshold behaves completely differently under bursty load — so those are asserted equal
     * to the config repo's values, while the sizes and the wait are deliberately smaller and
     * asserted as such.
     */
    @Test
    void theTestInstanceMirrorsProductionSemanticsAndDiffersOnlyInTiming() {
        CircuitBreakerConfig config = breaker(INSTANCE).getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowType())
                .as("production uses COUNT_BASED; a TIME_BASED window is a different machine")
                .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getFailureRateThreshold())
                .as("production's failureRateThreshold is 50")
                .isEqualTo(50f);

        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
                .as("deliberately far below production's 10s — this is the only thing that would "
                        + "make the cycle below take tens of seconds")
                .isLessThan(Duration.ofSeconds(1).toMillis());
    }

    /**
     * Verifies the real instances' shared default config still carries production's numbers.
     *
     * <p>The twelve Feign breakers all inherit {@code baseConfig=default}, so this is the one place
     * where a config-repo edit — dropping the predicate, widening the window, raising the threshold
     * — would change the behaviour of every client at once. Asserting the predicate by <em>type</em>
     * rather than by behaviour is intentional: it is the wiring that the 2026-08-05 fix added, and a
     * behavioural check alone would pass against any predicate that happened to agree on the cases
     * tested.
     */
    @Test
    void theRealInstancesInheritProductionsDefaultConfig() {
        CircuitBreakerConfig config =
                breaker("photo-app-users-service-isActive").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowType())
                .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);

        assertThat(config.getRecordExceptionPredicate().test(
                new ApplicationException("gone", HttpStatus.NOT_FOUND)))
                .as("recordFailurePredicate must still be DownstreamFailurePredicate: a 4xx is the "
                        + "callee working correctly, not an outage. This is the 2026-08-05 fix.")
                .isFalse();
        assertThat(config.getRecordExceptionPredicate().test(new IllegalStateException("down")))
                .as("anything without a downstream 4xx must still count as a failure")
                .isTrue();
    }

    /** Verifies a breaker starts CLOSED, which is the only safe default: fail open, not shut. */
    @Test
    void aFreshBreakerIsClosed() {
        assertThat(breaker(INSTANCE).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * Verifies CLOSED → OPEN once the failure rate crosses the threshold with enough calls
     * recorded.
     *
     * <p>Four failures against {@code minimumNumberOfCalls=4} and a 50% threshold is a 100% rate, so
     * the transition is unambiguous. The instructive part is the preceding assertion: after three
     * failures the breaker is still CLOSED even though the rate is already 100%, because the minimum
     * has not been met. That guard is what stops a service opening its own circuit on the first
     * failure after startup, when one call is 100% of the sample.
     */
    @Test
    @DisplayName("CLOSED → OPEN, but not before minimumNumberOfCalls is reached")
    void theBreakerOpensOnceTheThresholdIsCrossed() {
        CircuitBreaker breaker = breaker(INSTANCE);

        drive(breaker, 3, 0);
        assertThat(breaker.getState())
                .as("100%% failure rate but only 3 of the required 4 calls — the minimum exists so "
                        + "one early failure cannot open a circuit on a 1-call sample")
                .isEqualTo(CircuitBreaker.State.CLOSED);

        drive(breaker, 1, 0);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** Verifies a failure rate below the threshold leaves the breaker CLOSED. */
    @Test
    void aFailureRateBelowTheThresholdKeepsItClosed() {
        CircuitBreaker breaker = breaker(INSTANCE);

        drive(breaker, 1, 3);

        assertThat(breaker.getMetrics().getFailureRate())
                .as("25% is below the 50% threshold")
                .isEqualTo(25f);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * Verifies an OPEN breaker rejects calls <em>without running them</em>.
     *
     * <p>The counter is the assertion that matters. "Threw CallNotPermittedException" alone would
     * also be true of a breaker that ran the call, let it fail, and then complained — and shedding
     * load is the entire purpose. If the downstream is still being contacted, an open circuit is
     * just a more confusing error message.
     */
    @Test
    void anOpenBreakerRejectsCallsWithoutRunningThem() {
        CircuitBreaker breaker = breaker(INSTANCE);
        drive(breaker, 4, 0);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        AtomicInteger timesActuallyRun = new AtomicInteger();

        assertThatThrownBy(() -> breaker.executeRunnable(timesActuallyRun::incrementAndGet))
                .isInstanceOf(CallNotPermittedException.class);

        assertThat(timesActuallyRun)
                .as("the whole point of an open circuit is that the downstream is left alone")
                .hasValue(0);
    }

    /**
     * Verifies OPEN → HALF_OPEN happens on the first call attempted after the wait elapses.
     *
     * <p>Faithful to production, which leaves {@code automaticTransitionFromOpenToHalfOpenEnabled}
     * at its default of false: nothing moves the breaker on a timer, the transition is evaluated
     * lazily when someone next tries. That distinction is worth pinning because it means a breaker
     * on an idle client can sit OPEN indefinitely past its wait duration and still report OPEN to
     * the health indicator — which looks like an unrecovered outage and is not one.
     *
     * <p>The acquired permit is released so the half-open budget is left intact for the tests
     * below.
     */
    @Test
    @DisplayName("OPEN → HALF_OPEN, lazily, on the first attempt after the wait")
    void theBreakerBecomesHalfOpenOnTheFirstAttemptAfterTheWait() {
        CircuitBreaker breaker = breaker(INSTANCE);
        drive(breaker, 4, 0);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(10))
                .until(breaker::tryAcquirePermission);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        breaker.releasePermission();
    }

    /**
     * Verifies HALF_OPEN → CLOSED once the permitted trial calls all succeed.
     *
     * <p>This is the recovery path, and it is the half most easily left broken: a breaker that opens
     * correctly but never closes turns a transient outage into a permanent one, and does it
     * silently, because from the caller's side a recovered downstream and a stuck breaker look
     * identical.
     */
    @Test
    @DisplayName("HALF_OPEN → CLOSED when the trial calls succeed")
    void successfulTrialCallsCloseTheBreaker() {
        CircuitBreaker breaker = breaker(INSTANCE);
        drive(breaker, 4, 0);
        breaker.transitionToHalfOpenState();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // permittedNumberOfCallsInHalfOpenState = 2
        drive(breaker, 0, 2);

        assertThat(breaker.getState())
                .as("a breaker that opens but never closes turns a blip into an outage")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * Verifies HALF_OPEN → OPEN when the trial calls show the downstream is still unhealthy.
     *
     * <p>One failure out of two permitted trials is a 50% rate, which meets the threshold. Without
     * this path a breaker would close on the first hopeful success and immediately flood a service
     * that has not actually recovered — the failure mode where the breaker makes the outage last
     * longer than it otherwise would.
     */
    @Test
    @DisplayName("HALF_OPEN → OPEN when the downstream is still unhealthy")
    void aFailedTrialCallReopensTheBreaker() {
        CircuitBreaker breaker = breaker(INSTANCE);
        drive(breaker, 4, 0);
        breaker.transitionToHalfOpenState();

        drive(breaker, 1, 1);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * Verifies the full cycle end to end: CLOSED → OPEN → HALF_OPEN → CLOSED, with the half-open
     * transition driven by real elapsed time rather than by a forced call.
     *
     * <p>Each leg is asserted above in isolation; this proves they compose, which is not implied. A
     * breaker that closes only when {@code transitionToHalfOpenState()} is called by hand — but
     * never when the wait expires naturally — would pass every test above and recover never in
     * production.
     */
    @Test
    @DisplayName("the whole cycle: CLOSED → OPEN → HALF_OPEN → CLOSED")
    void theCompleteCycleRecoversWithoutIntervention() {
        CircuitBreaker breaker = breaker(INSTANCE);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        drive(breaker, 4, 0);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(10))
                .until(() -> {
                    if (!breaker.tryAcquirePermission()) {
                        return false;
                    }
                    breaker.releasePermission();
                    return true;
                });
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        drive(breaker, 0, 2);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * Verifies the predicate applies inside the state machine, not only at the Feign layer.
     *
     * <p>{@link FeignResilienceMatrixTest} proves four hundred-and-four responses do not open the
     * real breakers, but it does so through the whole stack, where a passing result could also be
     * explained by the decoder, the fallback or the aspects. Here the exception is handed straight
     * to the breaker: four ApplicationException(404)s, more than {@code minimumNumberOfCalls}, and
     * the state must not move. That isolates the claim to {@code recordFailurePredicate} alone.
     */
    @Test
    void applicationExceptionsCarryingA4xxAreNotRecordedByTheBreakerItself() {
        CircuitBreaker breaker = breaker(INSTANCE);

        for (int i = 0; i < 4; i++) {
            catchThrowable(() -> breaker.executeRunnable(() -> {
                throw new ApplicationException("no such user", HttpStatus.NOT_FOUND);
            }));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
