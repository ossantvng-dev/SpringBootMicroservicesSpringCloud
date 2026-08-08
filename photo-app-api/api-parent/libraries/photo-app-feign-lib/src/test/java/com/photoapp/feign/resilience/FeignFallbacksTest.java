package com.photoapp.feign.resilience;

import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.feign.client.AccountFeignClient;
import com.photoapp.feign.client.AlbumFeignClient;
import com.photoapp.feign.client.PhotoFeignClient;
import com.photoapp.feign.client.UserFeignClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * All twelve fallback methods, plus the shared {@link FeignFallbacks#translate} they delegate to.
 *
 * <p>The fallbacks are {@code default} methods on the interfaces, so they can be invoked directly
 * on a trivial hand-written implementation — no Spring context, no proxy, no aspects. That is worth
 * doing separately from {@link FeignResilienceMatrixTest} because the matrix reaches the fallbacks
 * only through a failing HTTP call, which means it can only ever exercise the throwable shapes the
 * stack actually produces. Here every fallback is handed every shape.
 *
 * <p>Exit criterion for testing-plan.md Phase 4: "all 12 fallbacks <strong>throw</strong>
 * {@code ApplicationException} with 503". The emphasis on <em>throw</em> is the point — these
 * methods declare a return type and never return, which is unusual enough that a reader could
 * reasonably expect a null or a default value instead. A fallback that quietly returned
 * {@code false} from {@code isActiveFallback} would let a deactivated user's account be created
 * during a users-service outage.
 */
class FeignFallbacksTest {

    /*
        Bare implementations. Only the default fallback methods are inherited and exercised; the
        abstract Feign methods are never called, so the stubs below are unreachable by design.
     */
    private static final UserFeignClient USERS = new UserFeignClient() {
        public boolean isActive(Long id) {
            throw new UnsupportedOperationException();
        }

        public com.photoapp.entity.User findByUsernameAndActiveUser(String username) {
            throw new UnsupportedOperationException();
        }

        public com.photoapp.commons.dto.user.UserDTO findById(Long id) {
            throw new UnsupportedOperationException();
        }
    };

    private static final AccountFeignClient ACCOUNTS = new AccountFeignClient() {
        public com.photoapp.commons.dto.account.AccountDTO findById(Long id) {
            throw new UnsupportedOperationException();
        }

        public org.springframework.data.domain.Page<com.photoapp.commons.dto.account.AccountDTO>
        findAll(Map<String, String> filters) {
            throw new UnsupportedOperationException();
        }

        public com.photoapp.commons.dto.account.AccountDTO activateOrDeactivate(Long id, boolean activate) {
            throw new UnsupportedOperationException();
        }

        public void deleteById(Long id) {
            throw new UnsupportedOperationException();
        }

        public void deleteByUserId(Long userId) {
            throw new UnsupportedOperationException();
        }
    };

    private static final AlbumFeignClient ALBUMS = new AlbumFeignClient() {
        public com.photoapp.commons.dto.album.AlbumDTO findById(Long id) {
            throw new UnsupportedOperationException();
        }

        public org.springframework.data.domain.Page<com.photoapp.commons.dto.album.AlbumDTO>
        findAll(Map<String, String> filters) {
            throw new UnsupportedOperationException();
        }

        public com.photoapp.commons.dto.album.AlbumDTO activateOrDeactivate(Long id, boolean activate) {
            throw new UnsupportedOperationException();
        }

        public void deleteById(Long id) {
            throw new UnsupportedOperationException();
        }

        public void deleteByAccountIds(List<Long> accountIds) {
            throw new UnsupportedOperationException();
        }

        public long countByAccountId(Long accountId) {
            throw new UnsupportedOperationException();
        }
    };

    private static final PhotoFeignClient PHOTOS = new PhotoFeignClient() {
        public com.photoapp.commons.dto.photo.PhotoDTO findById(Long id) {
            throw new UnsupportedOperationException();
        }

        public org.springframework.data.domain.Page<com.photoapp.commons.dto.photo.PhotoDTO>
        findAll(Map<String, String> filters) {
            throw new UnsupportedOperationException();
        }

        public com.photoapp.commons.dto.photo.PhotoDTO activateOrDeactivate(Long id, boolean activate) {
            throw new UnsupportedOperationException();
        }

        public void deleteById(Long id) {
            throw new UnsupportedOperationException();
        }

        public void deleteByAlbumIds(List<Long> albumIds) {
            throw new UnsupportedOperationException();
        }

        public long countByAlbumIds(List<Long> albumIds) {
            throw new UnsupportedOperationException();
        }
    };

    /** Every fallback, as {@code (label, expected service name, invocation taking the cause)}. */
    static Stream<Arguments> allTwelveFallbacks() {
        return Stream.of(
                fallback("UserFeignClient#isActiveFallback", "users",
                        t -> USERS.isActiveFallback(1L, t)),
                fallback("UserFeignClient#findByUsernameAndActiveUserFallback", "users",
                        t -> USERS.findByUsernameAndActiveUserFallback("ada", t)),
                fallback("UserFeignClient#findByIdFallback", "users",
                        t -> USERS.findByIdFallback(1L, t)),
                fallback("AccountFeignClient#findByIdFallback", "accounts",
                        t -> ACCOUNTS.findByIdFallback(1L, t)),
                fallback("AccountFeignClient#findAllFallback", "accounts",
                        t -> ACCOUNTS.findAllFallback(Map.of(), t)),
                fallback("AccountFeignClient#deleteByUserIdFallback", "accounts",
                        t -> ACCOUNTS.deleteByUserIdFallback(1L, t)),
                fallback("AlbumFeignClient#findByIdFallback", "albums",
                        t -> ALBUMS.findByIdFallback(1L, t)),
                fallback("AlbumFeignClient#findAllFallback", "albums",
                        t -> ALBUMS.findAllFallback(Map.of(), t)),
                fallback("AlbumFeignClient#deleteByAccountIdsFallback", "albums",
                        t -> ALBUMS.deleteByAccountIdsFallback(List.of(1L), t)),
                fallback("AlbumFeignClient#countByAccountIdFallback", "albums",
                        t -> ALBUMS.countByAccountIdFallback(1L, t)),
                fallback("PhotoFeignClient#deleteByAlbumIdsFallback", "photos",
                        t -> PHOTOS.deleteByAlbumIdsFallback(List.of(1L), t)),
                fallback("PhotoFeignClient#countByAlbumIdsFallback", "photos",
                        t -> PHOTOS.countByAlbumIdsFallback(List.of(1L), t))
        );
    }

    private static Arguments fallback(String label, String service, ThrowingConsumer<Throwable> invoke) {
        return Arguments.of(label, service, invoke);
    }

    /**
     * Verifies every fallback throws a 503 when the cause is a genuine outage.
     *
     * <p>This is the Phase 4 exit criterion. Twelve methods is enough that one written to
     * {@code return null} instead of {@code throw} is entirely plausible, and it would fail
     * differently for each caller: {@code countByAccountId}'s would surface as a
     * {@code NullPointerException} unboxing to {@code long}, while {@code findById}'s would flow on
     * as a null DTO and fail somewhere unrelated.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allTwelveFallbacks")
    void everyFallbackThrows503WhenTheDownstreamReallyFailed(
            String label, String service, ThrowingConsumer<Throwable> invoke) {

        Throwable outage = new IOException("connection refused");

        assertThatThrownBy(() -> invoke.accept(outage))
                .as("%s must THROW, not return a default. A silent null or false from a fallback "
                        + "is a wrong answer presented as a right one.", label)
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getHttpStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * REGRESSION GUARD (2026-08-05): verifies every fallback passes a downstream 4xx through
     * unchanged instead of replacing it with 503.
     *
     * <p>Resilience4j invokes a {@code fallbackMethod} for <em>any</em> throwable, including one
     * that merely carries a 404 the downstream answered correctly. Before
     * {@code FeignFallbacks.translate}, all twelve discarded that and reported "…is not available",
     * which told an operator a healthy service was down. Asserting the identity of the exception —
     * not just its status — pins that the original is passed through rather than reconstructed,
     * so the downstream's own message survives too.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allTwelveFallbacks")
    void everyFallbackPassesADownstream4xxThroughUnchanged(
            String label, String service, ThrowingConsumer<Throwable> invoke) {

        ApplicationException downstreamSaidNo =
                new ApplicationException("no such user", HttpStatus.NOT_FOUND);

        assertThatThrownBy(() -> invoke.accept(downstreamSaidNo))
                .as("%s replaced the downstream's own 404 with a blanket 503", label)
                .isSameAs(downstreamSaidNo);
    }

    /**
     * Verifies each fallback's 503 message names the service that failed and the operation that
     * was attempted.
     *
     * <p>All twelve share one {@code translate} call and differ only in the two strings they pass
     * it, which is exactly the kind of copy-paste that ends up with the albums fallback reporting
     * the accounts service. The message is the only thing distinguishing twelve otherwise identical
     * exceptions in a log.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allTwelveFallbacks")
    void everyFallbackNamesItsOwnServiceAndOperation(
            String label, String service, ThrowingConsumer<Throwable> invoke) {

        String operation = label.substring(label.indexOf('#') + 1).replace("Fallback", "");

        assertThatThrownBy(() -> invoke.accept(new IOException("down")))
                .hasMessageContaining("photo-app-" + service + "-service")
                .as("%s should name the operation it was standing in for", label)
                .hasMessageContaining(operation);
    }

    /**
     * Verifies {@code translate} finds a 4xx nested inside a wrapped cause chain.
     *
     * <p>The case that actually occurs: by the time a throwable reaches a fallback it has usually
     * been wrapped by the aspects, so the {@code ApplicationException} is not the outermost. A
     * {@code translate} that only checked the top level would pass the two parameterized tests
     * above — which hand it unwrapped exceptions — and still flatten every real 404 to 503 in
     * production.
     */
    @Test
    void translateUnwrapsANestedDownstream4xx() {
        ApplicationException real = new ApplicationException("forbidden", HttpStatus.FORBIDDEN);
        Throwable wrapped = new RuntimeException("aspect", new IllegalStateException("feign", real));

        assertThat(FeignFallbacks.translate(wrapped, "Failure on %s. gone", "findById"))
                .isSameAs(real);
    }

    /**
     * Verifies a nested 5xx is <em>not</em> passed through, but replaced by the 503 with the
     * service's own message.
     *
     * <p>The asymmetry is deliberate and worth pinning: {@code translate} keys off
     * {@code is4xxClientError()}, so only client errors are preserved. A downstream 500 is a real
     * failure and the caller is better served by "photo-app-users-service is not available" than by
     * the downstream's own internal error text, which describes a system the caller cannot act on.
     */
    @Test
    void translateReplacesANested5xxWithTheServiceUnavailableMessage() {
        Throwable wrapped = new RuntimeException("aspect",
                new ApplicationException("NPE in users-service", HttpStatus.INTERNAL_SERVER_ERROR));

        ApplicationException translated =
                FeignFallbacks.translate(wrapped, "Failure on %s. The photo-app-users-service is not available", "isActive");

        assertThat(translated.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(translated.getMessage())
                .isEqualTo("Failure on isActive. The photo-app-users-service is not available");
    }

    /** Verifies a null cause produces the 503 rather than a {@code NullPointerException}. */
    @Test
    void translateHandlesANullThrowable() {
        assertThat(FeignFallbacks.translate(null, "Failure on %s. gone", "isActive").getHttpStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
