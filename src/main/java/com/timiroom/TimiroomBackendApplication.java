package com.timiroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class TimiroomBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(TimiroomBackendApplication.class, args);
	}

}
