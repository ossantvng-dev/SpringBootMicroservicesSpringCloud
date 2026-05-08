package com.photoapp.accounts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableDiscoveryClient
@EnableJpaAuditing
@EnableFeignClients(basePackages = "com.photoapp.commons.feign")
@ComponentScan(basePackages = {"com.photoapp.accounts", "com.photoapp.commons"})
public class PhotoAppAccountsServiceApplication {

	static void main(String[] args) { SpringApplication.run(PhotoAppAccountsServiceApplication.class, args); }

}
