package com.photoapp.feign.resilience;

import com.photoapp.commons.exception.ApplicationException;

import java.util.function.Predicate;

/*
    Decides what counts as a FAILURE OF THE DOWNSTREAM SERVICE for circuit-breaker and
    retry purposes.

    A 4xx from downstream is not a failure of that service - it is that service working
    correctly and rejecting the request. "User not found", "forbidden" and "bad request"
    all mean the callee is healthy.

    Without this, ordinary user error trips the breaker. Measured before this class
    existed: five logins with unknown usernames (each a 404 from users-service, decoded
    into ApplicationException by CustomFeignErrorDecoder) pushed
    photo-app-users-service-findByUsernameAndActiveUser past
    minimumNumberOfCalls=5 / failureRateThreshold=50, opening the circuit - after which a
    VALID admin login also failed with 503. Any user could deny login to everyone by
    mistyping a username five times.

    Retries have the same problem in miniature: a 404 will still be a 404 on the third
    attempt, so retrying it just triples the load and the latency for a guaranteed failure.
 */
public class DownstreamFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        Throwable current = throwable;
        // The aspect may hand us a wrapped exception, so walk the cause chain.
        while (current != null) {
            if (current instanceof ApplicationException applicationException) {
                return !applicationException.getHttpStatus().is4xxClientError();
            }
            current = current.getCause();
        }
        // Anything not carrying a downstream status - connect timeouts, unknown host,
        // 5xx - is a genuine failure and must count.
        return true;
    }
}
