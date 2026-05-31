package com.photoapp.albums;

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
@ComponentScan(basePackages = {"com.photoapp.albums", "com.photoapp.commons", "com.photoapp.security"})
public class PhotoAppAlbumsServiceApplication {

	static void main(String[] args) {
		SpringApplication.run(PhotoAppAlbumsServiceApplication.class, args);
	}

}
