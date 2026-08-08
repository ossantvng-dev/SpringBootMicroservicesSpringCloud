package com.photoapp.feign.decoder;

import com.photoapp.commons.exception.ApplicationException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * All five branches of {@link CustomFeignErrorDecoder}.
 *
 * <p>A unit test with no Spring context, because the decoder is a pure function from a
 * {@code Response} to an {@code Exception} and every branch is reachable directly. The Feign
 * integration — that this decoder is the one actually installed on the clients — is proved
 * separately by the client suites, which observe its output through a real HTTP call.
 *
 * <p>The status carried on the resulting {@link ApplicationException} is what the whole downstream
 * chain then keys off: {@link com.photoapp.feign.resilience.DownstreamFailurePredicate} decides
 * whether to open a circuit by asking {@code isit4xxClientError()}, and
 * {@code FeignFallbacks.translate} decides what the caller is told by asking the same question. A
 * status set wrongly here is therefore not a cosmetic error — it changes whether an outage is
 * detected.
 */
class CustomFeignErrorDecoderTest {

    private final CustomFeignErrorDecoder decoder = new CustomFeignErrorDecoder();

    private static Response responseWithStatus(int status) {
        return Response.builder()
                .status(status)
                .reason("stubbed")
                .request(Request.create(Request.HttpMethod.GET, "/users/1",
                        Collections.emptyMap(), null, StandardCharsets.UTF_8, new RequestTemplate()))
                .headers(Collections.emptyMap())
                .build();
    }

    /**
     * Verifies the three explicitly-handled client errors each keep their own status.
     *
     * <p>These three are the ones that must survive intact, because they are the ones
     * {@code DownstreamFailurePredicate} relies on to <em>not</em> open a circuit. Collapsing any of
     * them into a 500 — an easy thing to do when adding a branch — would restore the 2026-08-05
     * defect for that status alone, and it would only show up as a circuit opening under ordinary
     * user error.
     */
    @ParameterizedTest(name = "{0} → ApplicationException({0})")
    @CsvSource({"401", "403", "404"})
    void clientErrorsKeepTheirOwnStatus(int status) {
        Exception decoded = decoder.decode("UserFeignClient#findById(Long)", responseWithStatus(status));

        assertThat(decoded).isInstanceOf(ApplicationException.class);
        assertThat(((ApplicationException) decoded).getHttpStatus().value())
                .as("a %d must not be rewritten; the predicate and the fallbacks both branch on "
                        + "whether the status is 4xx", status)
                .isEqualTo(status);
        assertThat(decoded).hasMessageContaining("UserFeignClient#findById(Long)");
    }

    /**
     * Verifies a downstream 503 is reported as 503.
     *
     * <p>The one branch where "pass the status through" and "report unavailability" happen to agree,
     * which makes it the branch most likely to be broken without anyone noticing — the caller sees
     * 503 either way. It is asserted separately from the default branch below precisely because the
     * two produce the same-looking outcome for different reasons.
     */
    @Test
    void serviceUnavailableIsReportedAsServiceUnavailable() {
        Exception decoded = decoder.decode("AlbumFeignClient#findById(Long)", responseWithStatus(503));

        assertThat(((ApplicationException) decoded).getHttpStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Verifies every unlisted status falls to the default branch and becomes a 500.
     *
     * <p>This is the branch that decides how a circuit breaker sees the long tail. 500 is not 4xx,
     * so {@code DownstreamFailurePredicate} counts it as a genuine failure — which is right for 502
     * and 504, and is a deliberate call for 400, 409 and 429. A 429 in particular is the downstream
     * asking to be called less; counting it as an outage and opening the circuit is arguably the
     * correct response anyway, but it is worth knowing that is what happens.
     *
     * <p>The 400 case is the one to be aware of: it is a client error the decoder does <em>not</em>
     * treat as one, so a run of malformed requests opens the circuit exactly the way mistyped
     * usernames used to before the 2026-08-05 fix. Nothing in the codebase sends a downstream 400
     * today, which is why this is recorded here rather than logged as a defect.
     */
    @ParameterizedTest(name = "{0} → 500 via the default branch")
    @ValueSource(ints = {400, 409, 418, 429, 500, 502, 504})
    void anyOtherStatusFallsToTheDefaultBranchAsAnInternalError(int status) {
        Exception decoded = decoder.decode("PhotoFeignClient#countByAlbumIds(List)",
                responseWithStatus(status));

        assertThat(((ApplicationException) decoded).getHttpStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(decoded)
                .as("the original status belongs in the message, since it is not in the status")
                .hasMessageContaining(String.valueOf(status));
    }

    /**
     * Verifies the method key reaches the message on every branch.
     *
     * <p>It is the only thing in the exception that says <em>which</em> call failed. Without it a
     * 404 from an account lookup and a 404 from an album lookup produce identical messages, and
     * the operator reading the log has to guess.
     */
    @ParameterizedTest(name = "status {0} names the failing method")
    @ValueSource(ints = {401, 403, 404, 503, 500})
    void everyBranchNamesTheFailingMethod(int status) {
        Exception decoded = decoder.decode("AccountFeignClient#deleteByUserId(Long)",
                responseWithStatus(status));

        assertThat(decoded).hasMessageContaining("AccountFeignClient#deleteByUserId(Long)");
    }

    /**
     * CHARACTERIZATION: a status outside the IANA-registered set makes the decoder throw instead of
     * returning an exception to be thrown by Feign.
     *
     * <p>{@code HttpStatus.valueOf(int)} is the first statement in {@code decode} and it throws
     * {@code IllegalArgumentException} for an unrecognised code, before the switch is reached. An
     * {@code ErrorDecoder} is contractually supposed to <em>return</em> the exception; one that
     * throws sends a different exception up the stack than the one the caller's fallback is written
     * against — {@code FeignFallbacks.translate} would find no {@code ApplicationException} in the
     * chain and report 503.
     *
     * <p>Left as-is rather than fixed: no service in this system emits a non-standard status, so
     * this is only reachable from a misbehaving proxy or a future third-party downstream. Recorded
     * here so the behaviour is known rather than discovered during an incident, and noted in
     * backlog.txt as LOW.
     */
    @Test
    @DisplayName("a non-standard status escapes as IllegalArgumentException, not ApplicationException")
    void anUnrecognisedStatusCodeThrowsOutOfTheDecoder() {
        assertThatThrownBy(() -> decoder.decode("UserFeignClient#isActive(Long)", responseWithStatus(599)))
                .as("CHARACTERIZATION: HttpStatus.valueOf runs before the switch and rejects "
                        + "unregistered codes. If this starts failing, the decoder learned to "
                        + "handle them — assert the new behaviour instead.")
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(ApplicationException.class);
    }
}
