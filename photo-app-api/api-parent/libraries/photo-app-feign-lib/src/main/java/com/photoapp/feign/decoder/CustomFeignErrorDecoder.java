package com.photoapp.feign.decoder;

import com.photoapp.commons.exception.ApplicationException;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/* Your decoders in the feign-lib handle the HTTP response when the remote service responds. */
@Component
public class CustomFeignErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        HttpStatus status = HttpStatus.valueOf(response.status());
        return switch (status) {
            case UNAUTHORIZED -> new ApplicationException("Unauthorized when calling " + methodKey, HttpStatus.UNAUTHORIZED);
            case FORBIDDEN -> new ApplicationException("Forbidden when calling " + methodKey, HttpStatus.FORBIDDEN);
            case NOT_FOUND -> new ApplicationException("Resource not found when calling " + methodKey, HttpStatus.NOT_FOUND);
            case SERVICE_UNAVAILABLE -> new ApplicationException("Service unavailable when calling " + methodKey, HttpStatus.SERVICE_UNAVAILABLE);
            default -> new ApplicationException("Downstream error (" + status + ") when calling " + methodKey, HttpStatus.INTERNAL_SERVER_ERROR);
        };
    }
}
