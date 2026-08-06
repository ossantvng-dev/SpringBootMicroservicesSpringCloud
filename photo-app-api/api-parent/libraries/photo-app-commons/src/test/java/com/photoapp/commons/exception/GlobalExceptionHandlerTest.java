package com.photoapp.commons.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.photoapp.commons.dto.ApiErrorDTO;
import com.photoapp.commons.support.LogCapture;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for every handler on {@link GlobalExceptionHandler} - status, full
 * {@link ApiErrorDTO} shape, and the log marker and level each one emits.
 *
 * <p>Plain JUnit + Mockito, no Spring context: this module cannot depend on
 * {@code photo-app-test-support}, which depends on it, and Maven rejects module cycles
 * regardless of scope. See {@code docs/TESTING.md} §1.
 *
 * <p>Handler <em>resolution</em> - that {@code AccessDeniedException} lands on
 * {@link GlobalExceptionHandler#accessDeniedHandler} rather than the catch-all - is pinned
 * separately in {@link GlobalExceptionHandlerResolutionTest}, because calling a handler method
 * directly proves what it does but not that Spring would ever call it.
 */
class GlobalExceptionHandlerTest {

    private static final String PATH = "/users/42";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ------------------------------------------------------------------ #1

    @Nested
    @DisplayName("#1 ApplicationException")
    class ApplicationExceptionHandler {

        /*
            The whole point of ApplicationException is that it carries its own status. A test
            that only checked 404 would pass against a handler that hardcoded 404, which is
            exactly the class of bug this handler exists to avoid.
         */
        @ParameterizedTest(name = "{0} propagates unchanged")
        @CsvSource({
                "BAD_REQUEST,           Invalid page number",
                "FORBIDDEN,             Not your album",
                "NOT_FOUND,             User not found",
                "CONFLICT,              Account has albums",
                "SERVICE_UNAVAILABLE,   Users service unavailable"
        })
        void propagatesItsOwnStatus(HttpStatus status, String message) {
            ResponseEntity<?> response =
                    handler.applicationExceptionHandler(new ApplicationException(message, status), request());

            assertShape(response, status, message);
        }

        @Test
        void logsApplicationExceptionAtWarnWithoutAStackTrace() {
            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.applicationExceptionHandler(
                        new ApplicationException("User not found", HttpStatus.NOT_FOUND), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("APPLICATION_EXCEPTION")
                        .contains("path=" + PATH, "status=404 NOT_FOUND", "message=User not found");
                assertThat(event.getThrowableProxy()).isNull();
            }
        }
    }

    // ------------------------------------------------------------------ #2

    @Nested
    @DisplayName("#2 MethodArgumentNotValidException")
    class BodyValidation {

        @Test
        void joinsEveryFieldErrorAndReturns400() {
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Payload(), "payload");
            binding.addError(new FieldError("payload", "email", "must not be blank"));
            binding.addError(new FieldError("payload", "age", "must be positive"));

            ResponseEntity<?> response = handler.validationExceptionHandler(
                    new MethodArgumentNotValidException(aMethodParameter(), binding), request());

            assertShape(response, HttpStatus.BAD_REQUEST,
                    "email: must not be blank; age: must be positive");
        }

        /* The orElse branch: a binding result with only global errors has no field errors. */
        @Test
        void fallsBackWhenThereAreNoFieldErrors() {
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Payload(), "payload");

            ResponseEntity<?> response = handler.validationExceptionHandler(
                    new MethodArgumentNotValidException(aMethodParameter(), binding), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Validation error");
        }

        @Test
        void logsAtWarn() {
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Payload(), "payload");
            binding.addError(new FieldError("payload", "email", "must not be blank"));

            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.validationExceptionHandler(
                        new MethodArgumentNotValidException(aMethodParameter(), binding), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("VALIDATION_ERROR")
                        .contains("path=" + PATH, "email: must not be blank");
                assertThat(event.getThrowableProxy()).isNull();
            }
        }
    }

    // ------------------------------------------------------------------ #3

    @Nested
    @DisplayName("#3 ConstraintViolationException")
    class ConstraintViolations {

        /*
            Built from a real Validator rather than a mocked ConstraintViolation: the handler
            renders `v.getPropertyPath()`, and a Mockito mock's Path would stringify as
            "Mock for Path" - the assertion would then pass while proving nothing about the
            message a client actually receives.
         */
        @Test
        void rendersPropertyPathAndMessageAndReturns400() {
            Set<ConstraintViolation<Payload>> violations = validator.validate(new Payload());

            ResponseEntity<?> response = handler.constraintViolationHandler(
                    new ConstraintViolationException(violations), request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(bodyOf(response).getMessage()).isEqualTo("email: must not be blank");
        }

        /*
            Two violations, so the `(a, b) -> a + "; " + b` join actually runs. With a
            single-violation payload reduce never calls the BiFunction and that lambda stays
            uncovered - which is precisely what JaCoCo reported on the first run of this suite.
            Order is not asserted: getConstraintViolations() returns a Set.
         */
        @Test
        void joinsMultipleViolations() {
            Set<ConstraintViolation<TwoFieldPayload>> violations =
                    validator.validate(new TwoFieldPayload());

            ResponseEntity<?> response = handler.constraintViolationHandler(
                    new ConstraintViolationException(violations), request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(bodyOf(response).getMessage())
                    .contains("email: must not be blank")
                    .contains("username: must not be blank")
                    .contains("; ");
        }

        @Test
        void fallsBackWhenThereAreNoViolations() {
            ResponseEntity<?> response = handler.constraintViolationHandler(
                    new ConstraintViolationException(Set.of()), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Validation error");
        }

        @Test
        void logsAtWarn() {
            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.constraintViolationHandler(
                        new ConstraintViolationException(validator.validate(new Payload())), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).startsWith("CONSTRAINT_VIOLATION");
                assertThat(event.getThrowableProxy()).isNull();
            }
        }
    }

    // ------------------------------------------------------------------ #4

    @Nested
    @DisplayName("#4 DataAccessException")
    class DatabaseFailure {

        /* The real message may name tables, columns or the connection string. It must not
           reach the client - only the generic text does. */
        @Test
        void returns500WithAGenericMessage() {
            ResponseEntity<?> response = handler.dataAccessExceptionHandler(
                    new DataAccessResourceFailureException(
                            "Could not open connection to jdbc:mysql://photo-app-mysql:3306/photo_app"),
                    request());

            assertShape(response, HttpStatus.INTERNAL_SERVER_ERROR, "Database error occurred");
            assertThat(bodyOf(response).getMessage()).doesNotContain("jdbc:mysql");
        }

        @Test
        void logsAtErrorWithTheStackTrace() {
            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.dataAccessExceptionHandler(
                        new DataAccessResourceFailureException("connection refused"), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .startsWith("DATABASE_ERROR")
                        .contains("connection refused");
                assertThat(event.getThrowableProxy()).isNotNull();
            }
        }
    }

    // ------------------------------------------------------------------ #5

    @Nested
    @DisplayName("#5 OptimisticLockException")
    class OptimisticLock {

        @Test
        void returns409() {
            ResponseEntity<?> response = handler.optimisticLockExceptionHandler(
                    new OptimisticLockException("Row was updated by another transaction"), request());

            assertShape(response, HttpStatus.CONFLICT, "Concurrent update detected. Please retry.");
        }

        /* A lost update race is an expected outcome under concurrency, not a server fault. */
        @Test
        void logsAtWarnWithoutAStackTrace() {
            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.optimisticLockExceptionHandler(new OptimisticLockException("stale"), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).startsWith("OPTIMISTIC_LOCK_CONFLICT");
                assertThat(event.getThrowableProxy()).isNull();
            }
        }
    }

    // ------------------------------------------------------------------ #6

    @Nested
    @DisplayName("#6 AccessDeniedException - the Step 8 regression")
    class AccessDenied {

        @Test
        void returns403() {
            ResponseEntity<?> response = handler.accessDeniedHandler(
                    new AccessDeniedException("Access Denied"), request());

            assertShape(response, HttpStatus.FORBIDDEN, "Forbidden");
        }

        /* The shape @PreAuthorize actually throws. */
        @Test
        void returns403ForTheAuthorizationDeniedSubclass() {
            ResponseEntity<?> response = handler.accessDeniedHandler(
                    new AuthorizationDeniedException("Access Denied"), request());

            assertShape(response, HttpStatus.FORBIDDEN, "Forbidden");
        }

        /*
            WARN, not ERROR - a rejected authorization is an expected outcome. Logging it at
            ERROR with a stack trace would bury genuine faults in noise every time a user
            touched an endpoint they lack the role for.
         */
        @Test
        void logsAccessDeniedAtWarnWithoutAStackTrace() {
            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.accessDeniedHandler(new AuthorizationDeniedException("Access Denied"), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("ACCESS_DENIED")
                        .contains("path=" + PATH);
                assertThat(event.getFormattedMessage()).doesNotContain("UNHANDLED_EXCEPTION");
                assertThat(event.getThrowableProxy()).isNull();
            }
        }
    }

    // ------------------------------------------------------------------ #7

    @Nested
    @DisplayName("#7 generic catch-all")
    class CatchAll {

        @Test
        void returns500WithAGenericMessage() {
            ResponseEntity<?> response = handler.genericExceptionHandler(
                    new IllegalStateException("NullPointerException at UserServiceImpl.java:88"), request());

            assertShape(response, HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred");
            assertThat(bodyOf(response).getMessage()).doesNotContain("UserServiceImpl");
        }

        /* This one SHOULD be ERROR with a stack trace - it is the genuine-fault channel. */
        @Test
        void logsAtErrorWithTheStackTrace() {
            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.genericExceptionHandler(new IllegalStateException("boom"), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .startsWith("UNHANDLED_EXCEPTION")
                        .contains("path=" + PATH, "message=boom");
                assertThat(event.getThrowableProxy()).isNotNull();
            }
        }
    }

    // ---------------------------------------------------------------- #8-11
    // Added 2026-08-06. Every one of these returned 500 with UNHANDLED_EXCEPTION at ERROR
    // before the handlers existed - verified against the running stack, 8/8.

    @Nested
    @DisplayName("#8 MethodArgumentTypeMismatchException")
    class TypeMismatch {

        /* GET /users/abc against @PathVariable Long. */
        @Test
        void returns400ForAnUnparseablePathVariable() {
            ResponseEntity<?> response = handler.typeMismatchHandler(
                    typeMismatch("abc", Long.class, "id"), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Parameter 'id' must be of type Long");
        }

        /* PATCH /users/1/activate?activate=maybe against a boolean @RequestParam - the same
           exception, because path variables and query parameters share one conversion path. */
        @Test
        void returns400ForAnUnconvertibleQueryParameter() {
            ResponseEntity<?> response = handler.typeMismatchHandler(
                    typeMismatch("maybe", boolean.class, "activate"), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Parameter 'activate' must be of type boolean");
        }

        /* getRequiredType() is @Nullable - the fallback branch, otherwise permanently yellow. */
        @Test
        void degradesGracefullyWhenTheRequiredTypeIsUnknown() {
            ResponseEntity<?> response = handler.typeMismatchHandler(
                    typeMismatch("abc", null, "id"), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Parameter 'id' must be of type the expected type");
        }

        @Test
        void logsAtWarnAndNotAsUnhandled() {
            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.typeMismatchHandler(typeMismatch("abc", Long.class, "id"), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("TYPE_MISMATCH")
                        .contains("parameter=id", "value=abc", "requiredType=Long")
                        .doesNotContain("UNHANDLED_EXCEPTION");
                assertThat(event.getThrowableProxy()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("#9 NoResourceFoundException")
    class NoResourceFound {

        @Test
        void returns404() {
            ResponseEntity<?> response = handler.noResourceFoundHandler(
                    new NoResourceFoundException(HttpMethod.GET, "/users/1/does-not-exist",
                            "No static resource users/1/does-not-exist."),
                    request());

            assertShape(response, HttpStatus.NOT_FOUND, "Resource not found");
        }

        @Test
        void logsAtWarnAndNotAsUnhandled() {
            HttpServletRequest request = request();
            when(request.getMethod()).thenReturn("GET");

            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.noResourceFoundHandler(
                        new NoResourceFoundException(HttpMethod.GET, "/nope", "No static resource nope."),
                        request);

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("RESOURCE_NOT_FOUND")
                        .contains("path=" + PATH, "method=GET")
                        .doesNotContain("UNHANDLED_EXCEPTION");
                assertThat(event.getThrowableProxy()).isNull();
            }
        }
    }

    @Nested
    @DisplayName("#10 HttpMessageNotReadableException")
    class MalformedBody {

        /* Jackson's message names the target class and parser state. It is logged, never returned. */
        @Test
        void returns400WithoutLeakingTheParserMessage() {
            ResponseEntity<?> response = handler.messageNotReadableHandler(malformedBody(), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Malformed request body");
            assertThat(bodyOf(response).getMessage()).doesNotContain("CreateUserInputDTO");
        }

        @Test
        void logsTheRealReasonAtWarnAndNotAsUnhandled() {
            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.messageNotReadableHandler(malformedBody(), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("MALFORMED_REQUEST_BODY")
                        .contains("Unexpected end-of-input")
                        .doesNotContain("UNHANDLED_EXCEPTION");
                assertThat(event.getThrowableProxy()).isNull();
            }
        }

        private HttpMessageNotReadableException malformedBody() {
            return new HttpMessageNotReadableException(
                    "JSON parse error: Unexpected end-of-input in CreateUserInputDTO",
                    new MockHttpInputMessage("{\"broken".getBytes()));
        }
    }

    @Nested
    @DisplayName("#11 HttpRequestMethodNotSupportedException")
    class MethodNotAllowed {

        @Test
        void returns405() {
            ResponseEntity<?> response = handler.methodNotSupportedHandler(
                    new HttpRequestMethodNotSupportedException("DELETE", Set.of("POST")), request());

            assertShape(response, HttpStatus.METHOD_NOT_ALLOWED,
                    "Method DELETE is not supported for this endpoint");
        }

        @Test
        void logsAtWarnAndNotAsUnhandled() {
            try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
                handler.methodNotSupportedHandler(
                        new HttpRequestMethodNotSupportedException("DELETE"), request());

                ILoggingEvent event = logs.onlyEvent();
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("METHOD_NOT_ALLOWED")
                        .contains("path=" + PATH, "method=DELETE")
                        .doesNotContain("UNHANDLED_EXCEPTION");
                assertThat(event.getThrowableProxy()).isNull();
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /** Every handler stamps the URI onto the response, so every one needs this stub. */
    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(PATH);
        return request;
    }

    private static ApiErrorDTO bodyOf(ResponseEntity<?> response) {
        assertThat(response.getBody()).isInstanceOf(ApiErrorDTO.class);
        return (ApiErrorDTO) response.getBody();
    }

    /**
     * The full contract of {@code buildResponse}: the status appears on the response AND twice
     * inside the body, the path is echoed, and a timestamp is present. `timeStamp` is asserted
     * for plausibility rather than equality - it is the one volatile field.
     */
    private static void assertShape(ResponseEntity<?> response, HttpStatus status, String message) {
        LocalDateTime before = LocalDateTime.now().minusMinutes(1);

        assertThat(response.getStatusCode()).isEqualTo(status);

        ApiErrorDTO body = bodyOf(response);
        assertThat(body.getHttpStatus()).isEqualTo(status.value());
        assertThat(body.getError()).isEqualTo(status.getReasonPhrase());
        assertThat(body.getMessage()).isEqualTo(message);
        assertThat(body.getPath()).isEqualTo(PATH);
        assertThat(body.getTimeStamp())
                .isNotNull()
                .isAfter(before)
                .isBefore(LocalDateTime.now().plusMinutes(1));
    }

    private static MethodArgumentTypeMismatchException typeMismatch(
            Object value, Class<?> requiredType, String name) {

        return new MethodArgumentTypeMismatchException(
                value, requiredType, name, aMethodParameter(),
                new NumberFormatException("For input string: \"" + value + "\""));
    }

    /** Any real MethodParameter will do; several Spring exceptions require a non-null one. */
    private static MethodParameter aMethodParameter() {
        try {
            return new MethodParameter(
                    GlobalExceptionHandlerTest.class.getDeclaredMethod("aTargetMethod", String.class), 0);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static void aTargetMethod(String argument) {
        // Reflection target only - never invoked.
    }

    static class Payload {
        @NotBlank(message = "must not be blank")
        private String email;
    }

    static class TwoFieldPayload {
        @NotBlank(message = "must not be blank")
        private String email;

        @NotBlank(message = "must not be blank")
        private String username;
    }
}
