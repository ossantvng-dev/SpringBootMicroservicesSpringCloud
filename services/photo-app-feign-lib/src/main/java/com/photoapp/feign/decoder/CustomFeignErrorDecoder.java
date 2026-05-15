package com.photoapp.feign.decoder;

import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CustomFeignErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        HttpStatus status = HttpStatus.valueOf(response.status());
        return switch (status) {
            case UNAUTHORIZED -> new RuntimeException("Unauthorized when calling " + methodKey);
            case FORBIDDEN -> new RuntimeException("Forbidden when calling " + methodKey);
            case NOT_FOUND -> new RuntimeException("Resource not found when calling " + methodKey);
            default -> new RuntimeException("Downstream error (" + status + ") when calling " + methodKey);
        };
    }
}
