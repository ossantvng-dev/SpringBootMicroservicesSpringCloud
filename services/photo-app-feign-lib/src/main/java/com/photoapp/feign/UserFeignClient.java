package com.photoapp.feign;

import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.entity.User;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "photo-app-users-service")
public interface UserFeignClient {

    @GetMapping("/users/{id}/active")
    boolean isActive(@PathVariable("id") Long id);

    @GetMapping("/users/username/{username}")
    User findByUsernameAndActiveUser(@PathVariable("username") String username);

    @GetMapping("/users/{id}")
    UserDTO findById(@PathVariable("id") Long id);


}
