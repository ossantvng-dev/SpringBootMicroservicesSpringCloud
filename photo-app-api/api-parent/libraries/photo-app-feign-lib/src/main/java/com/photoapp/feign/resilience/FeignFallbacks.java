package com.photoapp.feign.resilience;

import com.photoapp.commons.exception.ApplicationException;
import org.springframework.http.HttpStatus;

/*
    Shared translation for every Feign fallback.

    Resilience4j invokes a fallbackMethod for ANY throwable, including one that merely
    carries a downstream 4xx. The fallbacks used to discard that and report a blanket
    503 "<service> is not available", which was actively wrong: a deactivated user
    refreshing their token was told users-service was down, when users-service was
    healthy and had correctly answered 404.

    So: preserve a downstream 4xx as-is, and only report SERVICE_UNAVAILABLE when the
    downstream really did fail or could not be reached.

    Note this is separate from DownstreamFailurePredicate - that one decides whether the
    circuit OPENS, this one decides what the CALLER is told. Both are needed.
 */
public final class FeignFallbacks {

    private FeignFallbacks() {
    }

    public static ApplicationException translate(Throwable throwable, String errorTemplate, String operation) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ApplicationException applicationException
                    && applicationException.getHttpStatus().is4xxClientError()) {
                // The downstream service answered, and its answer was "no". Pass it through.
                return applicationException;
            }
            current = current.getCause();
        }
        return new ApplicationException(
                String.format(errorTemplate, operation),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }
}
