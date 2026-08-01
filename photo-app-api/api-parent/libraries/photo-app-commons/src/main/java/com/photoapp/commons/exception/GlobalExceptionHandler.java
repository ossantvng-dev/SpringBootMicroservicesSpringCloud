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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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