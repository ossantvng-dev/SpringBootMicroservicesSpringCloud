package com.photoapp.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.photoapp.feign")
@EntityScan(basePackages = "com.photoapp.entity")
@ComponentScan(basePackages = {"com.photoapp.auth", "com.photoapp.commons"})
public class PhotoAppAuthorizationServiceApplication {

	static void main(String[] args) {
		SpringApplication.run(PhotoAppAuthorizationServiceApplication.class, args);
	}

}
