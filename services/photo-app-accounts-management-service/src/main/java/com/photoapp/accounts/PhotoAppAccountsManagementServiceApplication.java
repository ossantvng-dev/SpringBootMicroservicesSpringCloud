package com.photoapp.accounts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication
public class PhotoAppAccountsManagementServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PhotoAppAccountsManagementServiceApplication.class, args);
	}

}
