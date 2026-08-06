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
                Arguments.of(new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null),
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

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("exceptionsAndTheirHandlers")
    void resolvesToTheExpectedHandler(Exception exception, String expectedHandlerMethod) {
        Method resolved = resolver.resolveMethod(exception);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo(expectedHandlerMethod);
    }

    /*
        Stated as its own test rather than left implicit in the table above, because this exact
        pairing is what regressed: the assertion that matters is not only "resolves to #6" but
        "does NOT resolve to #7".
     */
    @Test
    @DisplayName("@PreAuthorize denial resolves to accessDeniedHandler, NOT the catch-all")
    void authorizationDeniedNeverReachesTheCatchAll() {
        Method resolved = resolver.resolveMethod(new AuthorizationDeniedException("Access Denied"));

        assertThat(resolved.getName())
                .isEqualTo("accessDeniedHandler")
                .isNotEqualTo("genericExceptionHandler");
        assertThat(resolved.getAnnotation(ExceptionHandler.class).value())
                .containsExactly(AccessDeniedException.class);
    }

    /*
        Completeness guard. Adding an @ExceptionHandler without adding it to the table above
        fails here, so this suite cannot silently fall behind the class it describes.
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
