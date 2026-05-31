package com.photoapp.accounts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableDiscoveryClient
@EnableJpaAuditing
@EntityScan(basePackages = "com.photoapp.entity")
@EnableFeignClients(basePackages = "com.photoapp.feign")
@ComponentScan(basePackages = {"com.photoapp.accounts", "com.photoapp.commons", "com.photoapp.security"})
public class PhotoAppAccountsServiceApplication {

	static void main(String[] args) { SpringApplication.run(PhotoAppAccountsServiceApplication.class, args); }

}
