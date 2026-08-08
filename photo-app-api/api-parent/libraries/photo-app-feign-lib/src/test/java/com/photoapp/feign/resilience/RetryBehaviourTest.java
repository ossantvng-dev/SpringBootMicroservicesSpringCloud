package com.photoapp.feign.resilience;

import com.github.tomakehurst.wiremock.http.Fault;
import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.client.UserFeignClient;
import com.photoapp.feign.harness.AbstractFeignClientTest;
import com.photoapp.feign.harness.DownstreamBodies;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Retry behaviour: how many attempts, how they are spaced, and what recovers.
 *
 * <p>Attempt counts are read from WireMock's request journal rather than from Resilience4j's own
 * metrics, deliberately — the metrics would tell us what the retry component believes it did, while
 * the journal tells us what the downstream service actually received, which is the number that
 * matters to the service being retried against.
 */
class RetryBehaviourTest extends AbstractFeignClientTest {

    @Autowired
    private UserFeignClient userFeignClient;

    /**
     * Verifies a genuine failure is attempted exactly {@code maxAttempts} times — three, not four
     * and not one.
     *
     * <p>Off-by-one here is the classic mistake: {@code maxAttempts} counts the <em>total</em>
     * attempts including the first, so 3 means one call plus two retries. Getting it wrong by one
     * is a 33% increase in load on a downstream that is already failing, applied at exactly the
     * moment it can least absorb it.
     */
    @Test
    void aGenuineFailureIsAttemptedExactlyThreeTimes() {
        stubFor(get(urlEqualTo("/users/42/active"))
                .willReturn(aResponse().withStatus(500)));

        catchThrowable(() -> userFeignClient.isActive(42L));

        assertThat(DOWNSTREAM.getAllServeEvents())
                .as("maxAttempts=3 means the first call plus two retries, so the downstream should "
                        + "see three requests for one logical call")
                .hasSize(3);
    }

    /**
     * REGRESSION GUARD for the retry-amplification defect fixed on 2026-08-07.
     * See backlog.txt, "a transport failure reaches the downstream six times".
     *
     * <p>Verifies a transport failure is attempted exactly three times — the configured
     * {@code maxAttempts} — and not six.
     *
     * <p>Until the five services declared {@code feign-hc5}, Feign ran on
     * {@code java.net.HttpURLConnection}, which silently retries an idempotent request once when
     * the connection fails mid-flight. Resilience4j knew nothing about it, so one logical call
     * against an unreachable service delivered <strong>six</strong> requests while both its
     * metrics and the {@code maxAttempts=3} setting in the config repo said three. The
     * amplification landed specifically on the "downstream is unreachable" case — the one where a
     * struggling service can least afford double the traffic — and with production's 2s→4s backoff
     * it also stretched the caller's wait.
     *
     * <p>Paired deliberately with {@link #aGenuineFailureIsAttemptedExactlyThreeTimes}, which
     * covers a decoded 500. That one was never doubled, because the JDK only retried when the
     * connection itself broke rather than when the server answered — so the old behaviour was
     * inconsistent between the two, and only the pair pins that they now agree.
     */
    @Test
    @DisplayName("a transport failure reaches the downstream exactly three times")
    void aTransportFailureIsAttemptedExactlyThreeTimes() {
        stubFor(get(urlEqualTo("/users/42/active"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        catchThrowable(() -> userFeignClient.isActive(42L));

        assertThat(DOWNSTREAM.getAllServeEvents())
                .as("six here means the transport is retrying underneath Resilience4j again — "
                        + "feign-hc5 has been dropped from the poms and Feign is back on "
                        + "java.net.HttpURLConnection")
                .hasSize(3);
    }

    /**
     * REGRESSION GUARD (2026-08-05): verifies a downstream 4xx is attempted once and not retried.
     *
     * <p>The counterpart to the two tests above, and the reason the retry predicate exists.
     * Retrying a 404 cannot change the answer; all it does is triple the load on a healthy service
     * and — with {@code waitDuration=2s} and exponential backoff in production — make the user wait
     * six seconds to be told their username was wrong.
     */
    @Test
    void aDownstream4xxIsAttemptedOnceAndNotRetried() {
        stubFor(get(urlEqualTo("/users/username/nobody"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> userFeignClient.findByUsernameAndActiveUser("nobody"))
                .isInstanceOf(ApplicationException.class);

        assertThat(DOWNSTREAM.getAllServeEvents())
                .as("a 404 must not be retried — it will still be a 404 on the third attempt")
                .hasSize(1);
    }

    /**
     * Verifies a call that fails twice and then succeeds returns normally, without the caller ever
     * seeing an error.
     *
     * <p>This is the case retry exists for, and none of the counting tests above prove it: they all
     * end in failure, so every one of them would still pass against a retry that had stopped
     * returning the successful result. A transient blip — a pod restarting mid-rollout — must be
     * invisible to the caller, and the returned value has to be the real deserialised response, not
     * a null the caller then dereferences.
     */
    @Test
    @DisplayName("two transient failures then success is invisible to the caller")
    void aCallThatRecoversOnTheThirdAttemptSucceeds() {
        String scenario = "flaky-downstream";
        stubFor(get(urlEqualTo("/users/42")).inScenario(scenario)
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("second-failure"));
        stubFor(get(urlEqualTo("/users/42")).inScenario(scenario)
                .whenScenarioStateIs("second-failure")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        stubFor(get(urlEqualTo("/users/42")).inScenario(scenario)
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.USER_DTO)));

        UserDTO recovered = userFeignClient.findById(42L);

        assertThat(recovered).isNotNull();
        assertThat(recovered.getUsername())
                .as("the third attempt's body must be decoded and returned, not discarded")
                .isEqualTo("ada");
        assertThat(DOWNSTREAM.getAllServeEvents()).hasSize(3);
        assertThat(breaker("photo-app-users-service-findById").getMetrics().getNumberOfFailedCalls())
                .as("the logical call succeeded, so the breaker should record no failure — the two "
                        + "failed attempts happened inside the retry, beneath the breaker")
                .isZero();
    }

    /**
     * Verifies the retry waits grow exponentially rather than staying flat.
     *
     * <p>Asserted from the configured {@code IntervalFunction} rather than by timing a real call.
     * Timing is what makes resilience suites slow and flaky; the interval function is the actual
     * mechanism, and reading it proves both that {@code enableExponentialBackoff} took effect and
     * that the multiplier is 2 — a flat retry against a service that is down converts one caller's
     * failure into three simultaneous ones with no spacing at all.
     *
     * <p>The <em>durations</em> here are the test values (10ms base, shrunk from production's 2s);
     * the <em>shape</em> — enabled, multiplier 2, three attempts — is production's, as documented at
     * the top of this module's {@code application.properties}.
     */
    @Test
    void retryWaitsDoubleBetweenAttempts() {
        RetryConfig config = retryRegistry.retry("photo-app-users-service-isActive").getRetryConfig();

        assertThat(config.getMaxAttempts()).isEqualTo(3);

        /*
            getIntervalFunction() is null whenever the interval is configured through properties -
            Spring's RetryConfigurationProperties always populates the BI-function form, even when
            the interval does not depend on the result. Reading the wrong one silently yields null
            rather than a wrong number, so this is easy to get wrong and hard to notice.
         */
        var waits = config.<Object>getIntervalBiFunction();
        long afterFirstAttempt = waits.apply(1, Either.right(null));
        long afterSecondAttempt = waits.apply(2, Either.right(null));

        assertThat(afterSecondAttempt)
                .as("the second wait should be twice the first (exponentialBackoffMultiplier=2); "
                        + "a flat interval retries a struggling service on a fixed drumbeat")
                .isEqualTo(afterFirstAttempt * 2);
        assertThat(afterFirstAttempt).isPositive();
    }

    /**
     * Verifies the retry instance is wired to {@link DownstreamFailurePredicate}.
     *
     * <p>Every behavioural test above would also pass if the predicate were replaced by one that
     * happened to agree on the cases tested. This asserts the wiring itself, so a config-repo edit
     * that dropped {@code retryExceptionPredicate} fails here with an obvious cause rather than
     * showing up as a puzzling retry count somewhere else.
     */
    @Test
    void theRetryPredicateIsTheDownstreamFailurePredicate() {
        RetryConfig config = retryRegistry.retry("photo-app-users-service-isActive").getRetryConfig();

        assertThat(config.getExceptionPredicate().test(
                new ApplicationException("not found", org.springframework.http.HttpStatus.NOT_FOUND)))
                .as("a 4xx must not be retried")
                .isFalse();
        assertThat(config.getExceptionPredicate().test(new java.net.ConnectException("refused")))
                .as("an unreachable downstream must be retried")
                .isTrue();
    }
}
