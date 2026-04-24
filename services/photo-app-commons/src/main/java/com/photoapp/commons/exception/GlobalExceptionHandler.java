package com.photoapp.commons.exception;

import com.photoapp.commons.dto.ApiErrorDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<?> applicationExceptionHandler(
            ApplicationException applicationException, HttpServletRequest httpServletRequest) {
        return new ResponseEntity<>(
                apiErrorBuilder(applicationException.getMessage(),
                        applicationException.getHttpStatus(),
                        httpServletRequest.getRequestURI()),
                applicationException.getHttpStatus());
    }

    /* Thrown when validating @RequestBody with @Valid */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validationExceptionHandler(
            MethodArgumentNotValidException methodArgumentNotValidException,
            HttpServletRequest httpServletRequest) {
        String errors = methodArgumentNotValidException.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((msj1, msj2) -> msj1 + "; " + msj2)
                .orElse("Validation error");
        return new ResponseEntity<>(apiErrorBuilder(errors, HttpStatus.BAD_REQUEST, httpServletRequest.getRequestURI()),
                HttpStatus.BAD_REQUEST);
    }

    /* Thrown when validating individual params from a controller like @RequestParam or @PathVariable */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> constraintViolationHandler(
            ConstraintViolationException constraintViolationException,
            HttpServletRequest httpServletRequest) {
        String errors = constraintViolationException.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .reduce((msj1, msj2) -> msj1 + "; " + msj2)
                .orElse("Validation error");
        return new ResponseEntity<>(apiErrorBuilder(errors, HttpStatus.BAD_REQUEST, httpServletRequest.getRequestURI()),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> dataAccessExceptionHandler(
            DataAccessException dataAccessException, HttpServletRequest httpServletRequest) {
        return new ResponseEntity<>(apiErrorBuilder(dataAccessException.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR,
                httpServletRequest.getRequestURI()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> genericExceptionHandler(
            Exception exception, HttpServletRequest httpServletRequest) {
        return new ResponseEntity<>(apiErrorBuilder(exception.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR,
                httpServletRequest.getRequestURI()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ApiErrorDTO apiErrorBuilder(String message, HttpStatus status, String path) {
        return ApiErrorDTO.builder()
                .httpStatus(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(path)
                .timeStamp(LocalDateTime.now())
                .build();
    }

}
