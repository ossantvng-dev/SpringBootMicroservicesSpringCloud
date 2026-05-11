package com.photoapp.config.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
public class PhotoAppConfigurationServerApplication {

	static void main(String[] args) {
		SpringApplication.run(PhotoAppConfigurationServerApplication.class, args);
	}

}
