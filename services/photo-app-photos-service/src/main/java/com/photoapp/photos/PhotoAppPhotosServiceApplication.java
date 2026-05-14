package com.photoapp.photos;

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
@EnableFeignClients(basePackages = "com.photoapp.feign")
@EntityScan(basePackages = "com.photoapp.entity")
@ComponentScan(basePackages = {"com.photoapp.photos", "com.photoapp.commons", "com.photoapp.security"})
public class PhotoAppPhotosServiceApplication {

	static void main(String[] args) {
		SpringApplication.run(PhotoAppPhotosServiceApplication.class, args);
	}

}
