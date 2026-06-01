package com.photoapp.commons.exception;

import lombok.Getter;

@Getter
public class ServiceUnavailableException extends RuntimeException {

    private final String serviceName;
    private final String methodName;

    public ServiceUnavailableException(String serviceName, String methodName) {
        super(serviceName + " is unavailable when calling " + methodName);
        this.serviceName = serviceName;
        this.methodName = methodName;
    }
}
