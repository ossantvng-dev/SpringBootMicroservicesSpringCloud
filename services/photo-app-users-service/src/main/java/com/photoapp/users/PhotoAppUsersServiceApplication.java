package com.photoapp.users;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableDiscoveryClient
@EnableJpaAuditing
@SpringBootApplication
@ComponentScan(basePackages = {"com.photoapp.commons"})
public class PhotoAppUsersServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PhotoAppUsersServiceApplication.class, args);
	}

}
