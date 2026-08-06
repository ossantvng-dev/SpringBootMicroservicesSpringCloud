package com.photoapp.commons.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which handler Spring picks for a given exception.
 *
 * <p>This is the Step 8 regression test. That bug was not a handler behaving wrongly - the
 * 403 handler returned a perfectly good 403 whenever it ran. The bug was that it never ran:
 * {@code @PreAuthorize} throws {@link AuthorizationDeniedException} from <em>inside</em> the
 * DispatcherServlet, so it is matched by this advice long before it could reach Spring
 * Security's {@code ExceptionTranslationFilter}, and with no {@code AccessDeniedException}
 * handler present it was swallowed by {@code @ExceptionHandler(Exception.class)} and reported
 * as a 500. Calling {@code accessDeniedHandler} directly - as
 * {@link GlobalExceptionHandlerTest} does - would have passed throughout the defect.
 *
 * <p>{@link ExceptionHandlerMethodResolver} is the exact component {@code
 * ExceptionHandlerExceptionResolver} uses at runtime to make this choice, so this asserts the
 * real dispatch decision rather than a reimplementation of it.
 */
class GlobalExceptionHandlerResolutionTest {

    private final ExceptionHandlerMethodResolver resolver =
            new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    static Stream<Arguments> exceptionsAndTheirHandlers() {
        return Stream.of(
                Arguments.of(new ApplicationException("nope", HttpStatus.NOT_FOUND),
                        "applicationExceptionHandler"),
                Arguments.of(new MethodArgumentNotValidException(
                                aMethodParameter(), new BeanPropertyBindingResult(new Object(), "target")),
                        "validationExceptionHandler"),
                Arguments.of(new ConstraintViolationException(Set.of()),
                        "constraintViolationHandler"),
                Arguments.of(new DataAccessResourceFailureException("down"),
                        "dataAccessExceptionHandler"),
                Arguments.of(new OptimisticLockException("stale"),
                        "optimisticLockExceptionHandler"),
                Arguments.of(new AccessDeniedException("Access Denied"),
                        "accessDeniedHandler"),
                Arguments.of(new AuthorizationDeniedException("Access Denied"),
                        "accessDeniedHandler"),
                Arguments.of(new MethodArgumentTypeMismatchException(
                                "abc", Long.class, "id", aMethodParameter(), null),
                        "typeMismatchHandler"),
                Arguments.of(new NoResourceFoundException(HttpMethod.GET, "/nope", "No static resource nope."),
                        "noResourceFoundHandler"),
                Arguments.of(new HttpMessageNotReadableException("bad json",
                        new MockHttpInputMessage(new byte[0])), "messageNotReadableHandler"),
                Arguments.of(new HttpRequestMethodNotSupportedException("DELETE"),
                        "methodNotSupportedHandler"),
                // The catch-all must still be reachable for anything genuinely unmapped.
                Arguments.of(new IllegalStateException("boom"), "genericExceptionHandler"),
                Arguments.of(new NullPointerException("boom"), "genericExceptionHandler")
        );
    }

    /**
     * Verifies that for each of the thirteen exception shapes a running service can actually
     * produce, Spring selects the handler intended for it — and that the two deliberately
     * unmapped shapes (`IllegalStateException`, `NullPointerException`) still reach the
     * catch-all. Resolution is by closest match in the exception hierarchy, not by declaration
     * order, so a new handler catching too broad a type would steal traffic from a narrower one
     * and show up here as the wrong method name.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("exceptionsAndTheirHandlers")
    void resolvesToTheExpectedHandler(Exception exception, String expectedHandlerMethod) {
        Method resolved = resolver.resolveMethod(exception);

        assertThat(resolved)
                .as("Expected a handler method to be resolved for %s, but none was found",
                        exception.getClass().getSimpleName())
                .isNotNull();

        assertThat(resolved.getName()).isEqualTo(expectedHandlerMethod);
    }

    /**
     * The Step 8 regression guard. Verifies that an authenticated-but-unauthorized request —
     * a ROLE_USER token on an ADMIN-only endpoint, which `@PreAuthorize` rejects by throwing
     * `AuthorizationDeniedException` — resolves to `accessDeniedHandler` and **not** to the
     * generic catch-all. That exact pairing is what regressed in production: the 403 handler
     * was correct all along, it was simply never reached, so every role denial came back as a
     * 500. Also asserts the resolved method is the one registered for `AccessDeniedException`,
     * so a future handler that returned 403 by some other route would not quietly satisfy this.
     *
     * <p>Stated separately from the table above because the assertion that matters is not only
     * "resolves to #6" but "does NOT resolve to #7".
     */
    @Test
    @DisplayName("@PreAuthorize denial resolves to accessDeniedHandler, NOT the catch-all")
    void authorizationDeniedNeverReachesTheCatchAll() {
        Method resolved = resolver.resolveMethod(new AuthorizationDeniedException("Access Denied"));

        assertThat(resolved)
                .as("Expected a handler method to be resolved for AuthorizationDeniedException, "
                        + "but none was found")
                .isNotNull();

        assertThat(resolved.getName())
                .isEqualTo("accessDeniedHandler")
                .isNotEqualTo("genericExceptionHandler");

        ExceptionHandler annotation = resolved.getAnnotation(ExceptionHandler.class);

        assertThat(annotation)
                .as("Expected the resolved method %s to carry an @ExceptionHandler annotation, "
                        + "but it had none", resolved.getName())
                .isNotNull();

        assertThat(annotation.value()).containsExactly(AccessDeniedException.class);
    }

    /**
     * Verifies the suite has not fallen behind the class it describes: reflects over
     * `GlobalExceptionHandler`, collects every `@ExceptionHandler` method, and fails unless the
     * resolution table above names exactly that set. Adding a handler to production code
     * without adding a case here therefore breaks the build rather than passing silently with
     * an untested handler.
     *
     * <p>Not hypothetical — this test failed on its first run because `validationExceptionHandler`
     * had been left out of the table.
     */
    @Test
    @DisplayName("every @ExceptionHandler on the advice is covered by this test")
    void everyHandlerIsCovered() {
        Set<String> declared = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(ExceptionHandler.class))
                .map(Method::getName)
                .collect(Collectors.toSet());

        Set<String> covered = exceptionsAndTheirHandlers()
                .map(args -> (String) args.get()[1])
                .collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(declared).hasSize(11);
    }

    /** Any real MethodParameter will do; MethodArgumentNotValidException requires a non-null one. */
    private static MethodParameter aMethodParameter() {
        try {
            return new MethodParameter(
                    GlobalExceptionHandlerResolutionTest.class
                            .getDeclaredMethod("aTargetMethod", String.class), 0);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static void aTargetMethod(String argument) {
        // Reflection target only - never invoked.
    }
}
