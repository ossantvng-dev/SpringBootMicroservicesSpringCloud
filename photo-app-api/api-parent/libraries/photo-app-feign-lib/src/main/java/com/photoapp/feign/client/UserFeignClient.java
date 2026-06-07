package com.photoapp.feign.client;

import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.entity.User;
import com.photoapp.feign.configuration.FeignConfiguration;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "photo-app-users-service",
        configuration = FeignConfiguration .class
)
public interface UserFeignClient {

    String ERROR_TEMPLATE = "Failure on %s. The photo-app-users-service is not available";

    @GetMapping("/users/{id}/active")
    @Retry(name = "photo-app-users-service-isActive")
    @CircuitBreaker(name = "photo-app-users-service-isActive", fallbackMethod = "isActiveFallback")
    boolean isActive(@PathVariable("id") Long id);

    @GetMapping("/users/username/{username}")
    @Retry(name = "photo-app-users-service-findByUsernameAndActiveUser")
    @CircuitBreaker(
            name = "photo-app-users-service-findByUsernameAndActiveUser",
            fallbackMethod = "findByUsernameAndActiveUserFallback"
    )
    User findByUsernameAndActiveUser(@PathVariable("username") String username);

    @GetMapping("/users/{id}")
    @Retry(name = "photo-app-users-service-findById")
    @CircuitBreaker(
            name = "photo-app-users-service-findById",
            fallbackMethod = "findByIdFallback"
    )
    UserDTO findById(@PathVariable("id") Long id);

    default boolean isActiveFallback(Long id, Throwable t) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "isActive"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    default User findByUsernameAndActiveUserFallback(String username, Throwable t) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "findByUsernameAndActiveUser"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    default UserDTO findByIdFallback(Long id, Throwable t) {
        throw new ApplicationException(
                String.format(ERROR_TEMPLATE, "findById"),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

}
