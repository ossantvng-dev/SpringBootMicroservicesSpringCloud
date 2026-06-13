package com.photoapp.feign.decoder;

import com.photoapp.commons.exception.ApplicationException;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/*
    Your decoders in the feign-lib handle the HTTP response when the remote service responds.

    404 -> Not Found
    403 -> Forbidden
    401 -> Unauthorized
    503 -> Service Unavailable
*/
@Slf4j
@Component
public class CustomFeignErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        HttpStatus status = HttpStatus.valueOf(response.status());
        log.error("FEIGN ERROR method={} status={} reason={}", methodKey, status, response.reason());
        return switch (status) {
            case UNAUTHORIZED -> {
                log.warn("FEIGN UNAUTHORIZED calling {}", methodKey);
                yield new ApplicationException("Unauthorized when calling " + methodKey, HttpStatus.UNAUTHORIZED);
            }
            case FORBIDDEN -> {
                log.warn("FEIGN FORBIDDEN calling {}", methodKey);
                yield new ApplicationException("Forbidden when calling " + methodKey, HttpStatus.FORBIDDEN);
            }
            case NOT_FOUND -> {
                log.warn("FEIGN NOT_FOUND calling {}", methodKey);
                yield new ApplicationException("Resource not found when calling " + methodKey, HttpStatus.NOT_FOUND);
            }
            case SERVICE_UNAVAILABLE -> {
                log.error("FEIGN SERVICE UNAVAILABLE calling {}", methodKey);
                yield new ApplicationException("Service unavailable when calling " + methodKey, HttpStatus.SERVICE_UNAVAILABLE);
            }
            default -> {
                log.error("FEIGN UNKNOWN ERROR calling {} status={}", methodKey, status);
                yield new ApplicationException("Downstream error (" + status + ") when calling " + methodKey, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        };
    }
}
