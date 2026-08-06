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

        /**
         * Verifies that whatever status a service put into an `ApplicationException` is the
         * status the client receives, across the full range the codebase actually throws:
         * 400 for a bad page number, 403 for someone else's album, 404 for a missing user,
         * 409 for an account that still has albums, 503 for an unreachable downstream.
         *
         * <p>Five cases rather than one because this handler is a status *multiplexer* — every
         * business rule in every service funnels through it. A test that only checked 404 would
         * pass just as happily against a handler that hardcoded 404, which is exactly the class
         * of bug it exists to prevent.
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

        /**
         * Verifies that a business-rule rejection is logged as an expected outcome, not a fault:
         * one `APPLICATION_EXCEPTION` line at WARN carrying the path, status and message, and
         * **no stack trace attached**. A missing user is a normal thing for an API to say; if it
         * logged at ERROR with a trace, ordinary 404s would drown the signal that means
         * something is actually broken.
         */
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

        /**
         * Verifies that when `@Valid` rejects a request body on several fields at once, the
         * client gets 400 and a message listing **all** of them — `"email: must not be blank;
         * age: must be positive"` — rather than only the first. Two errors, not one, because a
         * handler that silently reported just the head of the list would pass a single-error
         * test and then make callers fix their payload one field per round trip.
         */
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

        /**
         * Verifies the empty case: a validation failure carrying no *field* errors — a
         * class-level or cross-field constraint, which binds to the object rather than a field —
         * still produces a 400 with the readable fallback `"Validation error"`, not an empty
         * message or a null body. This is the `orElse` branch of the `reduce`, and it is
         * reachable in production by any class-level constraint.
         */
        @Test
        void fallsBackWhenThereAreNoFieldErrors() {
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Payload(), "payload");

            ResponseEntity<?> response = handler.validationExceptionHandler(
                    new MethodArgumentNotValidException(aMethodParameter(), binding), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Validation error");
        }

        /**
         * Verifies that a rejected request body logs one `VALIDATION_ERROR` line at WARN,
         * including the field detail so the log is useful for spotting a broken client, and
         * with no stack trace. A caller sending an invalid payload is a client error; treating
         * it as a server fault is what made the pre-2026-08-06 logs unreadable.
         */
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

        /**
         * Verifies that a constraint violation raised outside request-body binding — the
         * `@Validated` method-parameter path, which throws a different exception than `@Valid`
         * on a body does — returns 400 with the property path and message a client can act on:
         * `"email: must not be blank"`.
         *
         * <p>Built from a real Hibernate Validator rather than a mocked `ConstraintViolation`,
         * because the handler renders `getPropertyPath()` and a Mockito mock's `Path` stringifies
         * as "Mock for Path". Mocking it would make the test pass while proving nothing about
         * the message a caller actually receives.
         */
        @Test
        void rendersPropertyPathAndMessageAndReturns400() {
            Set<ConstraintViolation<Payload>> violations = validator.validate(new Payload());

            ResponseEntity<?> response = handler.constraintViolationHandler(
                    new ConstraintViolationException(violations), request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(bodyOf(response).getMessage()).isEqualTo("email: must not be blank");
        }

        /**
         * Verifies that two simultaneous constraint violations are both reported, joined by
         * `"; "`, rather than one of them being dropped.
         *
         * <p>Exists because of a concrete gap: with a single-constraint payload, `reduce` never
         * invokes the joining function at all, so the `(a, b) -> a + "; " + b` lambda was
         * completely unexercised while this suite passed. JaCoCo flagged it on the first full
         * run. Order is deliberately not asserted — `getConstraintViolations()` returns a `Set`,
         * so pinning the order would make the test flaky for no benefit.
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

        /**
         * Verifies the degenerate case: a `ConstraintViolationException` carrying an empty
         * violation set still yields a well-formed 400 with `"Validation error"`, rather than
         * a 400 with a null or blank message. Defensive, but cheap — the exception's constructor
         * accepts an empty set, so nothing in the type system prevents this reaching the handler.
         */
        @Test
        void fallsBackWhenThereAreNoViolations() {
            ResponseEntity<?> response = handler.constraintViolationHandler(
                    new ConstraintViolationException(Set.of()), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Validation error");
        }

        /**
         * Verifies a constraint violation logs one `CONSTRAINT_VIOLATION` line at WARN with no
         * stack trace — the same client-error posture as the two validation handlers above, so
         * all three forms of "the caller sent something invalid" look alike in Kibana.
         */
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

        /**
         * Verifies that a database failure returns 500 with the fixed text `"Database error
         * occurred"` and **does not leak the underlying message to the client**. The test feeds
         * in a realistic failure whose message contains the JDBC URL — host, port and schema
         * name — and asserts that string is absent from the response body.
         *
         * <p>This is an information-disclosure guard, not a cosmetic one: Spring's
         * `DataAccessException` messages routinely carry table names, column names, SQL
         * fragments and connection strings, and this handler is reachable by any caller who can
         * trigger a query failure.
         */
        @Test
        void returns500WithAGenericMessage() {
            ResponseEntity<?> response = handler.dataAccessExceptionHandler(
                    new DataAccessResourceFailureException(
                            "Could not open connection to jdbc:mysql://photo-app-mysql:3306/photo_app"),
                    request());

            assertShape(response, HttpStatus.INTERNAL_SERVER_ERROR, "Database error occurred");
            assertThat(bodyOf(response).getMessage()).doesNotContain("jdbc:mysql");
        }

        /**
         * The mirror image of the client-error tests: verifies that a database failure logs
         * `DATABASE_ERROR` at **ERROR** *with* the stack trace attached, and that the real
         * message the client never saw is preserved in the log. A dead connection pool is a
         * genuine fault — this is the case where waking someone up is the correct behaviour, and
         * the trace is what makes it diagnosable.
         */
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

        /**
         * Verifies that a lost-update race — two transactions updating the same row, where JPA's
         * `@Version` check fails on the second — returns **409 Conflict** with a message telling
         * the caller to retry, rather than a 500. 409 is the correct signal because the request
         * was well-formed and may well succeed on a second attempt; a 500 would tell the client
         * to give up on something that is genuinely retryable.
         */
        @Test
        void returns409() {
            ResponseEntity<?> response = handler.optimisticLockExceptionHandler(
                    new OptimisticLockException("Row was updated by another transaction"), request());

            assertShape(response, HttpStatus.CONFLICT, "Concurrent update detected. Please retry.");
        }

        /**
         * Verifies an optimistic-lock conflict logs `OPTIMISTIC_LOCK_CONFLICT` at WARN with no
         * stack trace. Deliberate: under concurrent load a version clash is an *expected*
         * outcome of the locking strategy working correctly, so logging it at ERROR would make
         * the error rate track traffic volume rather than actual breakage.
         */
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

        /**
         * Verifies the base case of the Step 8 fix: a plain `AccessDeniedException` — what
         * Spring Security's filter chain raises when a request fails a path-level rule —
         * produces **403 Forbidden** with the body `"Forbidden"`, not a 500. The message is
         * deliberately bare: telling an unauthorized caller *why* they were refused leaks the
         * authorization model.
         */
        @Test
        void returns403() {
            ResponseEntity<?> response = handler.accessDeniedHandler(
                    new AccessDeniedException("Access Denied"), request());

            assertShape(response, HttpStatus.FORBIDDEN, "Forbidden");
        }

        /**
         * Verifies the case that actually broke in production. `@PreAuthorize` does not throw
         * `AccessDeniedException` itself — it throws the `AuthorizationDeniedException`
         * subclass, from method-security interception rather than the filter chain. This asserts
         * the subclass gets the same 403 treatment.
         *
         * <p>Separate from {@link #returns403()} because the two arrive by different routes, and
         * the one that regressed was this one. Which handler Spring *selects* for the subclass is
         * pinned in `GlobalExceptionHandlerResolutionTest`; here the concern is only that once
         * selected, it behaves identically.
         */
        @Test
        void returns403ForTheAuthorizationDeniedSubclass() {
            ResponseEntity<?> response = handler.accessDeniedHandler(
                    new AuthorizationDeniedException("Access Denied"), request());

            assertShape(response, HttpStatus.FORBIDDEN, "Forbidden");
        }

        /**
         * Verifies the observability half of the Step 8 fix: a role denial logs exactly one
         * `ACCESS_DENIED` line at WARN, with no stack trace and — asserted explicitly — without
         * the `UNHANDLED_EXCEPTION` marker anywhere in it.
         *
         * <p>The marker assertion is the point. Returning the right status is not enough: while
         * the defect was live, every ROLE_USER touching an ADMIN endpoint produced an ERROR with
         * a full trace, so the marker that is supposed to mean "something is broken" fired for
         * routine, correct authorization decisions.
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

        /**
         * Verifies the last-resort handler: anything not matched by a more specific handler
         * returns 500 with the fixed text `"Unexpected error occurred"` and leaks nothing about
         * the failure. The test's exception message deliberately looks like a stack frame
         * (`"NullPointerException at UserServiceImpl.java:88"`) and the assertion is that the
         * internal class name does **not** appear in the response body — internals belong in the
         * log, never in an HTTP response.
         */
        @Test
        void returns500WithAGenericMessage() {
            ResponseEntity<?> response = handler.genericExceptionHandler(
                    new IllegalStateException("NullPointerException at UserServiceImpl.java:88"), request());

            assertShape(response, HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred");
            assertThat(bodyOf(response).getMessage()).doesNotContain("UserServiceImpl");
        }

        /**
         * Verifies that the catch-all still logs `UNHANDLED_EXCEPTION` at ERROR **with** the
         * stack trace. This is the one handler where that is correct, and it is why every other
         * test in this class asserts the absence of that marker: `UNHANDLED_EXCEPTION` is meant
         * to be the "a real fault reached the edge" signal, and it is only trustworthy if
         * nothing else emits it. Deleting this test would make all the other log assertions
         * satisfiable by simply never logging at ERROR at all.
         */
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

        /**
         * Verifies that `GET /users/abc` against a `@PathVariable Long id` returns 400 with a
         * message naming both the parameter and the type expected — `"Parameter 'id' must be of
         * type Long"` — so the caller can see what they got wrong without guessing. Until
         * 2026-08-06 this was a 500, on roughly 20 `{id}` endpoints across the five services.
         */
        @Test
        void returns400ForAnUnparseablePathVariable() {
            ResponseEntity<?> response = handler.typeMismatchHandler(
                    typeMismatch("abc", Long.class, "id"), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Parameter 'id' must be of type Long");
        }

        /**
         * Verifies the same handler covers query parameters: `?activate=maybe` against a
         * `boolean @RequestParam` returns 400 naming `activate` and `boolean`. Included as a
         * distinct case because it *looks* like a separate bug from the outside — a caller
         * experiences a bad path segment and a bad query string as different mistakes — while
         * both are one exception, since Spring converts path variables and query parameters
         * through the same machinery.
         */
        @Test
        void returns400ForAnUnconvertibleQueryParameter() {
            ResponseEntity<?> response = handler.typeMismatchHandler(
                    typeMismatch("maybe", boolean.class, "activate"), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Parameter 'activate' must be of type boolean");
        }

        /**
         * Verifies the handler still produces a usable 400 when Spring cannot tell it what type
         * was expected: the message degrades to `"Parameter 'id' must be of type the expected
         * type"` instead of rendering "null" or throwing an NPE inside the error handler — the
         * worst possible place for one, since it would turn a 400 into a 500.
         *
         * <p>The null `requiredType` is intentional and API-sanctioned: Spring declares that
         * constructor parameter `@Nullable`, and `getRequiredType()` is documented to return
         * null when the target type is unknown. It therefore raises no nullability warning and
         * needs no suppression. Without this case the null-check branch is never taken and the
         * method shows yellow in JaCoCo.
         */
        @Test
        void degradesGracefullyWhenTheRequiredTypeIsUnknown() {
            ResponseEntity<?> response = handler.typeMismatchHandler(
                    typeMismatch("abc", null, "id"), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Parameter 'id' must be of type the expected type");
        }

        /**
         * Verifies a mistyped id logs `TYPE_MISMATCH` at WARN — carrying the parameter name, the
         * offending value and the expected type, which is what makes a misbehaving client
         * identifiable — and explicitly **not** `UNHANDLED_EXCEPTION` at ERROR. This is the half
         * of the 2026-08-06 fix that the status code does not show: before it, every typo in a
         * URL produced an ERROR with a full stack trace.
         */
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

        /**
         * Verifies that a request to a path no controller maps — the exception Spring's static
         * resource handler throws once every `@RequestMapping` has declined — returns **404**,
         * not the 500 it used to. The response says only `"Resource not found"`; the exception's
         * own message quotes the requested path back, and echoing an arbitrary caller-supplied
         * string into a response body is not worth the reflection risk when the `path` field
         * already carries it.
         */
        @Test
        void returns404() {
            ResponseEntity<?> response = handler.noResourceFoundHandler(
                    new NoResourceFoundException(HttpMethod.GET, "/users/1/does-not-exist",
                            "No static resource users/1/does-not-exist."),
                    request());

            assertShape(response, HttpStatus.NOT_FOUND, "Resource not found");
        }

        /**
         * Verifies an unmapped URL logs `RESOURCE_NOT_FOUND` at WARN with the path and HTTP
         * method, and never `UNHANDLED_EXCEPTION`. The method is logged because a 404 on `GET`
         * usually means a client typo, whereas a 404 on `POST` more often means a route was
         * renamed and a caller was not updated — worth being able to tell apart in Kibana.
         */
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

        /**
         * Verifies that an unparseable JSON body returns 400 with the generic `"Malformed
         * request body"`, and asserts the DTO class name embedded in Jackson's own message
         * (`CreateUserInputDTO`) is **absent** from the response.
         *
         * <p>An information-disclosure guard like the `DataAccessException` one, and it matters
         * more here: `POST /users` is reachable anonymously, so Jackson's message would let an
         * unauthenticated caller enumerate internal DTO class names and field types by sending
         * deliberately broken payloads.
         */
        @Test
        void returns400WithoutLeakingTheParserMessage() {
            ResponseEntity<?> response = handler.messageNotReadableHandler(malformedBody(), request());

            assertShape(response, HttpStatus.BAD_REQUEST, "Malformed request body");
            assertThat(bodyOf(response).getMessage()).doesNotContain("CreateUserInputDTO");
        }

        /**
         * Verifies the other side of the previous test: the parser detail withheld from the
         * client **is** written to the log, under `MALFORMED_REQUEST_BODY` at WARN. Withholding
         * it from the response is a security decision, not a reason to lose it — without this
         * assertion the handler could satisfy the disclosure test by discarding the reason
         * entirely, leaving nobody able to debug a genuinely broken client.
         */
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

        /**
         * Verifies that calling a real endpoint with the wrong verb — `DELETE` where only `POST`
         * is mapped — returns **405 Method Not Allowed**, naming the rejected method so the
         * caller can see the URL was right and the verb was not. Previously a 500, which told a
         * client the server was broken when in fact it was working exactly as designed.
         */
        @Test
        void returns405() {
            ResponseEntity<?> response = handler.methodNotSupportedHandler(
                    new HttpRequestMethodNotSupportedException("DELETE", Set.of("POST")), request());

            assertShape(response, HttpStatus.METHOD_NOT_ALLOWED,
                    "Method DELETE is not supported for this endpoint");
        }

        /**
         * Verifies a wrong-verb request logs `METHOD_NOT_ALLOWED` at WARN with the path and the
         * rejected method, never `UNHANDLED_EXCEPTION`. Worth logging at all rather than
         * silently returning 405: a sudden run of these usually means a client is calling an
         * endpoint whose verb changed, and the path plus method is enough to identify which.
         */
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
