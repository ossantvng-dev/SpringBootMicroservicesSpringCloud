package com.photoapp.feign.client;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.AccountTypeDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.harness.AbstractFeignClientTest;
import com.photoapp.feign.harness.DownstreamBodies;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Success paths for {@link AccountFeignClient}'s five methods.
 *
 * <p>Three of the five carry resilience annotations; {@code activateOrDeactivate} and
 * {@code deleteById} carry none at all, and that asymmetry is asserted here rather than assumed —
 * see {@link #deleteByIdHasNoFallbackSoFailuresSurfaceRaw}.
 */
class AccountFeignClientTest extends AbstractFeignClientTest {

    @Autowired
    private AccountFeignClient accountFeignClient;

    /**
     * Verifies {@code findById} deserialises an {@code AccountDTO}, {@code accountTypeDTO}
     * included.
     *
     * <p>That field earns its own assertion. {@code AccountMapper.toDTO} shipped a bug where
     * {@code accountType} silently failed to map to {@code accountTypeDTO} and arrived null —
     * testing-plan.md Phase 5 carries the regression test for the mapper itself. This is the same
     * field one layer out: if the enum fails to cross the wire, {@code AlbumServiceImpl} reads a
     * null account type on every album operation, since it calls this method on seven different
     * paths.
     */
    @Test
    void findByIdDeserialisesTheAccountIncludingItsType() {
        stubFor(get(urlEqualTo("/accounts/7"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.ACCOUNT_DTO)));

        AccountDTO account = accountFeignClient.findById(7L);

        assertThat(account).isNotNull();
        assertThat(account.getId()).isEqualTo(7L);
        assertThat(account.getUserId()).isEqualTo(42L);
        assertThat(account.getAccountName()).isEqualTo("ada-main");
        assertThat(account.getAccountTypeDTO())
                .as("accountTypeDTO arrived null across the wire. AlbumServiceImpl reads it on "
                        + "every album operation, and a null enum there is a NullPointerException "
                        + "in a service that never called accounts-service incorrectly.")
                .isEqualTo(AccountTypeDTO.PREMIUM);
        assertThat(account.getActiveAccount()).isTrue();
    }

    /**
     * Verifies {@code findAll} deserialises a Spring Data {@code Page} and expands its filter map
     * into query parameters.
     *
     * <p>Both halves are worth pinning. {@code Page} is an interface with no no-arg constructor, so
     * it only deserialises because {@code FeignAutoConfiguration}'s {@code pageJacksonModule} is on
     * the context — a dependency that is invisible until it is missing, at which point this fails
     * with a Jackson error rather than anything mentioning paging. And a {@code Map} bound to a
     * single {@code @RequestParam("filters")} does <em>not</em> serialise as
     * {@code ?filters=…}; Feign flattens each entry into its own parameter, which is what the
     * receiving controller's own {@code @RequestParam Map} expects.
     *
     * <p>Note this method has no caller anywhere in the reactor — see the inventory in
     * testing-plan.md Phase 4.
     */
    @Test
    void findAllDeserialisesAPageAndFlattensTheFilterMap() {
        stubFor(get(urlPathEqualTo("/accounts"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(DownstreamBodies.pageOf(DownstreamBodies.ACCOUNT_DTO))));

        Page<AccountDTO> page = accountFeignClient.findAll(Map.of("userId", "42"));

        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getAccountName()).isEqualTo("ada-main");

        verify(1, getRequestedFor(urlPathEqualTo("/accounts"))
                .withQueryParam("userId", equalTo("42")));
    }

    /** Verifies {@code deleteById} issues a DELETE to the per-id path and tolerates an empty 204. */
    @Test
    void deleteByIdSendsADeleteAndAcceptsAnEmptyBody() {
        stubFor(delete(urlEqualTo("/accounts/7"))
                .willReturn(aResponse().withStatus(204)));

        accountFeignClient.deleteById(7L);

        verify(1, deleteRequestedFor(urlEqualTo("/accounts/7")));
    }

    /**
     * Verifies the cascade delete reaches {@code /accounts/byUser/{userId}}.
     *
     * <p>This is the one {@code AccountFeignClient} method with a live caller:
     * {@code UserServiceImpl} fans out photos → albums → accounts when a user is deleted. A wrong
     * path here would leave orphaned accounts behind a user that no longer exists, and the delete
     * would still report success.
     */
    @Test
    void deleteByUserIdTargetsTheByUserPath() {
        stubFor(delete(urlEqualTo("/accounts/byUser/42"))
                .willReturn(aResponse().withStatus(204)));

        accountFeignClient.deleteByUserId(42L);

        verify(1, deleteRequestedFor(urlEqualTo("/accounts/byUser/42")));
    }

    /**
     * Verifies a downstream 404 keeps its status instead of being flattened to 503.
     *
     * <p>Regression guard for the {@code FeignFallbacks.translate} half of the 2026-08-05 fix, on
     * the client {@code AlbumServiceImpl} depends on most heavily — an album referencing an account
     * that no longer exists must read as "not found", not "accounts-service is down".
     */
    @Test
    void unknownAccountKeepsIts404RatherThanBecoming503() {
        stubFor(get(urlEqualTo("/accounts/404"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> accountFeignClient.findById(404L))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Documents, by assertion, that {@code deleteById} behaves differently under failure from its
     * three annotated siblings.
     *
     * <p>It has no {@code @CircuitBreaker}, no {@code @Retry} and no fallback, so a downstream 500
     * arrives as whatever {@code CustomFeignErrorDecoder} made of it — an
     * {@code ApplicationException(500)} — with no translation to 503 and no breaker protecting the
     * caller. That is not necessarily wrong, but it is inconsistent with the sibling methods and it
     * is currently invisible. Pinning it means the next person to add resilience annotations here
     * sees this test fail and makes the change deliberately.
     *
     * <p>{@code activateOrDeactivate} is the other unannotated method; it cannot reach a downstream
     * at all on the current transport, which {@code PatchVerbCharacterizationTest} covers.
     */
    @Test
    void deleteByIdHasNoFallbackSoFailuresSurfaceRaw() {
        stubFor(delete(urlEqualTo("/accounts/7"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> accountFeignClient.deleteById(7L))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .as("an unannotated method has no fallback, so the decoder's own status stands "
                        + "rather than being replaced by SERVICE_UNAVAILABLE")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // One attempt: no @Retry means no second try, unlike the annotated methods.
        verify(1, deleteRequestedFor(urlEqualTo("/accounts/7")));
    }
}
