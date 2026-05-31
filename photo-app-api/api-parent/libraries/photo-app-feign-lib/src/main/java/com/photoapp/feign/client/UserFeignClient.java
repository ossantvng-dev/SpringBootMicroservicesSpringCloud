package com.photoapp.feign.client;

import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.entity.User;
import com.photoapp.feign.configuration.FeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "photo-app-users-service",
        configuration = FeignConfiguration .class
)
public interface UserFeignClient {

    @GetMapping("/users/{id}/active")
    boolean isActive(@PathVariable("id") Long id);

    @GetMapping("/users/username/{username}")
    User findByUsernameAndActiveUser(@PathVariable("username") String username);

    @GetMapping("/users/{id}")
    UserDTO findById(@PathVariable("id") Long id);


}
