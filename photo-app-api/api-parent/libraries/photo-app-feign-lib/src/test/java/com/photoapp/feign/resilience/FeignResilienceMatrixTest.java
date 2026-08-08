package com.photoapp.feign.resilience;

import com.github.tomakehurst.wiremock.http.Fault;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.client.AccountFeignClient;
import com.photoapp.feign.client.AlbumFeignClient;
import com.photoapp.feign.client.PhotoFeignClient;
import com.photoapp.feign.client.UserFeignClient;
import com.photoapp.feign.harness.AbstractFeignClientTest;
import com.photoapp.feign.harness.DownstreamBodies;
import com.photoapp.feign.harness.ProtectedCall;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The heart of Phase 4: the same six properties asserted against all twelve resilience-protected
 * Feign methods.
 *
 * <p>Table-driven rather than hand-written because twelve methods times six properties is
 * seventy-two cases, and the one that gets skipped by hand is always the one that breaks. The
 * catalogue is proved complete by {@code FeignClientInventoryTest}, which walks the four interfaces
 * by reflection — so a thirteenth annotated method fails the build here rather than being quietly
 * untested.
 *
 * <p><strong>Three of these are regression guards for the 2026-08-05 defect</strong> in which five
 * logins with mistyped usernames opened the users-service circuit and the next <em>valid</em> login
 * got a 503 — a denial of service any user could trigger by accident. The fix was
 * {@link DownstreamFailurePredicate} (wired as {@code recordFailurePredicate} and
 * {@code retryExceptionPredicate}) plus {@link com.photoapp.feign.resilience.FeignFallbacks}
 * {@code .translate}. See backlog.txt and testing-plan.md §"Two fixes from 2026-08-05".
 *
 * <p>The other three exist because a predicate that is too permissive breaks the opposite way,
 * silently: if 4xx stopped counting <em>and so did everything else</em>, every test above would
 * still pass while the circuit breaker had quietly become decorative. Those three prove real
 * outages still trip it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeignResilienceMatrixTest extends AbstractFeignClientTest {

    @Autowired
    private UserFeignClient users;
    @Autowired
    private AccountFeignClient accounts;
    @Autowired
    private AlbumFeignClient albums;
    @Autowired
    private PhotoFeignClient photos;

    /** The twelve methods carrying {@code @CircuitBreaker} + {@code @Retry} + a fallback. */
    Stream<ProtectedCall> protectedCalls() {
        return Stream.of(
                new ProtectedCall("UserFeignClient#isActive",
                        "photo-app-users-service-isActive",
                        "GET", "/users/42/active", "true",
                        () -> users.isActive(42L)),
                new ProtectedCall("UserFeignClient#findByUsernameAndActiveUser",
                        "photo-app-users-service-findByUsernameAndActiveUser",
                        "GET", "/users/username/ada", DownstreamBodies.USER,
                        () -> users.findByUsernameAndActiveUser("ada")),
                new ProtectedCall("UserFeignClient#findById",
                        "photo-app-users-service-findById",
                        "GET", "/users/42", DownstreamBodies.USER_DTO,
                        () -> users.findById(42L)),
                new ProtectedCall("AccountFeignClient#findById",
                        "photo-app-accounts-service-findById",
                        "GET", "/accounts/7", DownstreamBodies.ACCOUNT_DTO,
                        () -> accounts.findById(7L)),
                new ProtectedCall("AccountFeignClient#findAll",
                        "photo-app-accounts-service-findAll",
                        "GET", "/accounts", DownstreamBodies.pagedResponseOf(DownstreamBodies.ACCOUNT_DTO),
                        () -> accounts.findAll(Map.of("userId", "42"))),
                new ProtectedCall("AccountFeignClient#deleteByUserId",
                        "photo-app-accounts-service-deleteByUserId",
                        "DELETE", "/accounts/byUser/42", null,
                        () -> {
                            accounts.deleteByUserId(42L);
                            return "void";
                        }),
                new ProtectedCall("AlbumFeignClient#findById",
                        "photo-app-albums-service-findById",
                        "GET", "/albums/11", DownstreamBodies.ALBUM_DTO,
                        () -> albums.findById(11L)),
                new ProtectedCall("AlbumFeignClient#findAll",
                        "photo-app-albums-service-findAll",
                        "GET", "/albums", DownstreamBodies.pagedResponseOf(DownstreamBodies.ALBUM_DTO),
                        () -> albums.findAll(Map.of("accountId", "7"))),
                new ProtectedCall("AlbumFeignClient#deleteByAccountIds",
                        "photo-app-albums-service-deleteByAccountIds",
                        "DELETE", "/albums/byAccountIds", null,
                        () -> {
                            albums.deleteByAccountIds(List.of(7L));
                            return "void";
                        }),
                new ProtectedCall("AlbumFeignClient#countByAccountId",
                        "photo-app-albums-service-countByAccountId",
                        "GET", "/albums/countByAccountId", "3",
                        () -> albums.countByAccountId(7L)),
                new ProtectedCall("PhotoFeignClient#deleteByAlbumIds",
                        "photo-app-photos-service-deleteByAlbumIds",
                        "DELETE", "/photos/byAlbumIds", null,
                        () -> {
                            photos.deleteByAlbumIds(List.of(11L));
                            return "void";
                        }),
                new ProtectedCall("PhotoFeignClient#countByAlbumIds",
                        "photo-app-photos-service-countByAlbumIds",
                        "GET", "/photos/countByAlbumIds", "7",
                        () -> photos.countByAlbumIds(List.of(11L)))
        );
    }

    /**
     * Verifies this catalogue covers every {@code @CircuitBreaker} on the four interfaces — no
     * more, no fewer.
     *
     * <p>Everything below is a matrix over the twelve entries above, so an omission here is not a
     * failing test, it is a silently missing one. Deriving the expected set by reflection rather
     * than restating it means a thirteenth annotated method breaks this test on the commit that
     * adds it, naming exactly what is untested.
     */
    @org.junit.jupiter.api.Test
    void theCatalogueCoversEveryCircuitBrokenMethod() {
        assertThat(protectedCalls().map(ProtectedCall::breakerName).sorted().toList())
                .as("a @CircuitBreaker method missing from this catalogue is not tested by any of "
                        + "the six properties below, and nothing else would report that")
                .isEqualTo(com.photoapp.feign.harness.FeignClientMetadata.allBreakerNames());
    }

    // =================================================================================
    // Regression guards for the 2026-08-05 "a 4xx tripped the breaker" defect
    // =================================================================================

    /**
     * REGRESSION GUARD (2026-08-05, "a downstream 4xx tripped the circuit breaker"):
     * verifies that six consecutive downstream 404s leave the circuit CLOSED.
     *
     * <p>Six is chosen against the real config: {@code minimumNumberOfCalls=5}, so five is the
     * fewest that can open a breaker and six proves the threshold was passed and still nothing
     * happened. Before {@link DownstreamFailurePredicate} was wired as {@code recordFailurePredicate}
     * this is exactly the shape that broke — five mistyped logins, each an honest 404 from a
     * perfectly healthy users-service, decoded into {@code ApplicationException} and counted as a
     * failure of the callee. The circuit opened and the next valid login got a 503.
     *
     * <p>The buffered-call count is asserted alongside the state because "still closed" alone is
     * satisfiable two ways — by a predicate that correctly excludes 4xx, or by a breaker that was
     * never invoked at all. Recording zero failures out of six <em>invoked</em> calls distinguishes
     * them.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedCalls")
    void downstream4xxDoesNotOpenTheCircuit(ProtectedCall call) {
        stubFor(request(call.httpMethod(), call.url())
                .willReturn(aResponse().withStatus(404)));

        for (int i = 0; i < 6; i++) {
            catchThrowable(call.invoke()::get);
        }

        CircuitBreaker breaker = breaker(call.breakerName());
        assertThat(breaker.getState())
                .as("Six downstream 404s opened %s. A 4xx means the callee answered correctly and "
                        + "said no; treating it as an outage lets ordinary user error deny the "
                        + "service to everyone. This is the 2026-08-05 defect.", call.breakerName())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("no 4xx should be recorded as a failure")
                .isZero();
    }

    /**
     * REGRESSION GUARD (2026-08-05): verifies a downstream 4xx is not retried.
     *
     * <p>The same predicate is wired as {@code retryExceptionPredicate}, and this is its other half.
     * A 404 will still be a 404 on the third attempt, so retrying it triples the load and the
     * latency on the downstream for a guaranteed failure — and with {@code maxAttempts=3} plus
     * exponential backoff, a mistyped username would have cost the user six seconds of waiting
     * before being told the name was wrong.
     *
     * <p>Exactly one request is the assertion, and it is what separates this from the previous test:
     * a predicate wired only to the circuit breaker and not to retry would pass that one and fail
     * this one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedCalls")
    void downstream4xxIsNotRetried(ProtectedCall call) {
        stubFor(request(call.httpMethod(), call.url())
                .willReturn(aResponse().withStatus(404)));

        catchThrowable(call.invoke()::get);

        assertThat(DOWNSTREAM.getAllServeEvents())
                .as("%s retried a 404. It will still be a 404 on the third attempt; retrying only "
                        + "multiplies load on a healthy downstream and delays the answer.",
                        call.label())
                .hasSize(1);
    }

    /**
     * REGRESSION GUARD (2026-08-05, the misleading-503 half): verifies a downstream 404 reaches the
     * caller as 404, not as {@code SERVICE_UNAVAILABLE}.
     *
     * <p>Resilience4j calls the fallback for <em>any</em> throwable, including one that merely
     * carries a downstream 4xx. The fallbacks used to discard that and report "…is not available",
     * so a deactivated user refreshing a token was told users-service was down while
     * users-service was healthy and had answered 404 correctly. {@code FeignFallbacks.translate}
     * passes a 4xx through untouched.
     *
     * <p>Note this is a genuinely separate concern from the predicate above: one decides whether the
     * circuit OPENS, this decides what the CALLER IS TOLD. A fix to either alone leaves the other
     * broken, which is why both are guarded.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedCalls")
    void downstream4xxReachesTheCallerWithItsOwnStatus(ProtectedCall call) {
        stubFor(request(call.httpMethod(), call.url())
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(call.invoke()::get)
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .as("%s reported the downstream's 404 as something else. Replacing it with 503 "
                        + "tells the operator a healthy service is down.", call.label())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Verifies the same pass-through for 403, not just 404.
     *
     * <p>Worth its own case because {@code CustomFeignErrorDecoder} handles 401, 403 and 404 in
     * three separate branches, and {@link DownstreamFailurePredicate} keys off
     * {@code is4xxClientError()} rather than off any one status. A predicate written against 404
     * alone would pass every test above and still flatten an authorization failure into "service
     * unavailable" — which is precisely the shape of the original 2026-08-05 report, where a 401
     * surfaced as a 503.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedCalls")
    void downstream403IsAlsoPassedThroughAndNotCounted(ProtectedCall call) {
        stubFor(request(call.httpMethod(), call.url())
                .willReturn(aResponse().withStatus(403)));

        for (int i = 0; i < 6; i++) {
            catchThrowable(call.invoke()::get);
        }

        assertThat(breaker(call.breakerName()).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThatThrownBy(call.invoke()::get)
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // =================================================================================
    // The property most at risk from the predicate change: real failures must still count
    // =================================================================================

    /**
     * Verifies a genuine downstream 5xx DOES open the circuit and DOES produce a 503.
     *
     * <p>This is the test that keeps the two above honest. {@link DownstreamFailurePredicate}
     * decides what counts as an outage, and the failure mode nobody would notice is a predicate
     * that became too permissive — if it started returning {@code false} for everything, all four
     * regression guards above would still pass while the circuit breaker had quietly stopped
     * protecting anything. A breaker that never opens fails silently and only in production, under
     * exactly the load it exists to shed.
     *
     * <p>Five logical calls is {@code minimumNumberOfCalls} with a 100% failure rate against a
     * 50% threshold, so the transition is unambiguous rather than borderline.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedCalls")
    void genuine5xxOpensTheCircuitAndYields503(ProtectedCall call) {
        stubFor(request(call.httpMethod(), call.url())
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 5; i++) {
            Throwable thrown = catchThrowable(call.invoke()::get);
            assertThat(thrown)
                    .as("a real downstream failure must reach the caller as an ApplicationException")
                    .isInstanceOf(ApplicationException.class);
            assertThat(((ApplicationException) thrown).getHttpStatus())
                    .as("%s reported a genuine 500 as something other than 503", call.label())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        assertThat(breaker(call.breakerName()).getState())
                .as("Five genuine 500s did NOT open %s. The predicate that stops 4xx counting has "
                        + "gone too far: real outages are no longer shedding load either.",
                        call.breakerName())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * Verifies a transport-level failure — connection reset, i.e. the downstream unreachable rather
     * than answering — also opens the circuit and yields 503.
     *
     * <p>Distinct from the 5xx case in a way that matters to the predicate's implementation: no
     * response is ever decoded, so no {@code ApplicationException} carrying a status exists in the
     * cause chain at all. {@link DownstreamFailurePredicate} walks that chain and falls through to
     * "anything not carrying a downstream status is a genuine failure" — the default branch. If that
     * default were ever inverted, connect timeouts and unknown hosts would stop counting, which is
     * the single most important thing a circuit breaker is for.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedCalls")
    void transportFailureOpensTheCircuitAndYields503(ProtectedCall call) {
        stubFor(request(call.httpMethod(), call.url())
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        for (int i = 0; i < 5; i++) {
            Throwable thrown = catchThrowable(call.invoke()::get);
            assertThat(thrown).isInstanceOf(ApplicationException.class);
            assertThat(((ApplicationException) thrown).getHttpStatus())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        assertThat(breaker(call.breakerName()).getState())
                .as("An unreachable downstream did not open %s. No response means no status in the "
                        + "cause chain, so this exercises the predicate's default branch — the one "
                        + "that must stay 'count it'.", call.breakerName())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * Verifies an open circuit sheds load: the downstream is no longer contacted, and the caller
     * still gets a well-formed 503 rather than a raw Resilience4j exception.
     *
     * <p>Both halves are the point of having a breaker at all. Not contacting the downstream is what
     * gives a struggling service room to recover; and {@code CallNotPermittedException} carries no
     * HTTP status, so it exercises the same default branch of {@code FeignFallbacks.translate} that
     * a transport failure does. Without that, the caller would see an internal Resilience4j type
     * leak out of a Feign client.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedCalls")
    void anOpenCircuitStopsCallingTheDownstreamAndStillReturns503(ProtectedCall call) {
        stubFor(request(call.httpMethod(), call.url())
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 5; i++) {
            catchThrowable(call.invoke()::get);
        }
        assertThat(breaker(call.breakerName()).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestsBefore = DOWNSTREAM.getAllServeEvents().size();

        assertThatThrownBy(call.invoke()::get)
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .as("an open circuit must still produce a proper 503 through the fallback, not "
                        + "leak CallNotPermittedException to the caller")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(DOWNSTREAM.getAllServeEvents())
                .as("%s kept calling a downstream it had already given up on — the breaker is "
                        + "open but not actually shedding load", call.label())
                .hasSize(requestsBefore);
    }
}
