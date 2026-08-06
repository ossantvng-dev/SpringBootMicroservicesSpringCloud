package com.photoapp.commons.exception;

import com.photoapp.commons.dto.ApiErrorDTO;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<?> applicationExceptionHandler(
            ApplicationException ex,
            HttpServletRequest request) {

        log.warn("APPLICATION_EXCEPTION path={} status={} message={}",
                request.getRequestURI(),
                ex.getHttpStatus(),
                ex.getMessage());

        return buildResponse(ex.getMessage(), ex.getHttpStatus(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validationExceptionHandler(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation error");

        log.warn("VALIDATION_ERROR path={} errors={}",
                request.getRequestURI(),
                errors);

        return buildResponse(errors, HttpStatus.BAD_REQUEST, request.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> constraintViolationHandler(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        String errors = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation error");

        log.warn("CONSTRAINT_VIOLATION path={} errors={}",
                request.getRequestURI(),
                errors);

        return buildResponse(errors, HttpStatus.BAD_REQUEST, request.getRequestURI());
    }

    /*
        The four handlers below all cover framework-thrown CLIENT input errors. Before they
        existed every one of them fell through to the #7 catch-all and was reported as a 500
        logged at ERROR with UNHANDLED_EXCEPTION and a full stack trace - measured against the
        running stack on 2026-08-05 (8/8 type-mismatch and no-handler cases) and again on
        2026-08-06 before this change (all five shapes below).

        A mistyped id in a URL is not a server fault, so: correct 4xx status, and WARN rather
        than ERROR, matching the other client-error handlers above. The log level is half the
        fix - ERROR here meant a client typo was indistinguishable from a real fault in Kibana.
     */

    /*
        GET /users/abc against @PathVariable Long, and ?activate=maybe against boolean, are
        both this exception - path variables and query parameters share one conversion path.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> typeMismatchHandler(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        String requiredType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "the expected type";
        String message = "Parameter '" + ex.getName() + "' must be of type " + requiredType;

        log.warn("TYPE_MISMATCH path={} parameter={} value={} requiredType={}",
                request.getRequestURI(),
                ex.getName(),
                ex.getValue(),
                requiredType);

        return buildResponse(message, HttpStatus.BAD_REQUEST, request.getRequestURI());
    }

    /*
        Spring MVC's last resort for an unmapped path: once no @RequestMapping matches, the
        request falls through to the static-resource handler, which throws this. A malformed
        URL is a 404, not a 500.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> noResourceFoundHandler(
            NoResourceFoundException ex,
            HttpServletRequest request) {

        log.warn("RESOURCE_NOT_FOUND path={} method={}",
                request.getRequestURI(),
                request.getMethod());

        return buildResponse("Resource not found", HttpStatus.NOT_FOUND, request.getRequestURI());
    }

    /*
        Unparseable or absent request body. The exception's own message carries Jackson
        internals (parser state, target class names), so it is logged but not returned.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> messageNotReadableHandler(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        log.warn("MALFORMED_REQUEST_BODY path={} message={}",
                request.getRequestURI(),
                ex.getMessage());

        return buildResponse("Malformed request body", HttpStatus.BAD_REQUEST, request.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> methodNotSupportedHandler(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {

        log.warn("METHOD_NOT_ALLOWED path={} method={}",
                request.getRequestURI(),
                ex.getMethod());

        return buildResponse(
                "Method " + ex.getMethod() + " is not supported for this endpoint",
                HttpStatus.METHOD_NOT_ALLOWED,
                request.getRequestURI()
        );
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> dataAccessExceptionHandler(
            DataAccessException ex,
            HttpServletRequest request) {

        log.error("DATABASE_ERROR path={} message={}",
                request.getRequestURI(),
                ex.getMessage(),
                ex);

        return buildResponse(
                "Database error occurred",
                HttpStatus.INTERNAL_SERVER_ERROR,
                request.getRequestURI()
        );
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<?> optimisticLockExceptionHandler(
            OptimisticLockException ex,
            HttpServletRequest request) {

        log.warn("OPTIMISTIC_LOCK_CONFLICT path={} message={}",
                request.getRequestURI(),
                ex.getMessage());

        return buildResponse(
                "Concurrent update detected. Please retry.",
                HttpStatus.CONFLICT,
                request.getRequestURI()
        );
    }

    /*
        Must stay above the catch-all. @PreAuthorize throws AuthorizationDeniedException,
        a subclass of AccessDeniedException, from inside the DispatcherServlet - so it is
        matched by @ExceptionHandler(Exception.class) long before it could propagate out
        to Spring Security's ExceptionTranslationFilter. Without this handler every
        insufficient-role denial is reported as a 500 instead of a 403.

        WARN, not ERROR: a rejected authorization is an expected outcome, not a fault.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> accessDeniedHandler(
            AccessDeniedException ex,
            HttpServletRequest request) {

        log.warn("ACCESS_DENIED path={} message={}",
                request.getRequestURI(),
                ex.getMessage());

        return buildResponse(
                "Forbidden",
                HttpStatus.FORBIDDEN,
                request.getRequestURI()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> genericExceptionHandler(
            Exception ex,
            HttpServletRequest request) {

        log.error("UNHANDLED_EXCEPTION path={} message={}",
                request.getRequestURI(),
                ex.getMessage(),
                ex);

        return buildResponse(
                "Unexpected error occurred",
                HttpStatus.INTERNAL_SERVER_ERROR,
                request.getRequestURI()
        );
    }

    private ResponseEntity<ApiErrorDTO> buildResponse(
            String message,
            HttpStatus status,
            String path) {

        return ResponseEntity.status(status)
                .body(ApiErrorDTO.builder()
                        .httpStatus(status.value())
                        .error(status.getReasonPhrase())
                        .message(message)
                        .path(path)
                        .timeStamp(LocalDateTime.now())
                        .build());
    }
}