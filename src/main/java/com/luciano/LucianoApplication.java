package com.luciano;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.luciano.config.LlmProperties;

@SpringBootApplication
@EnableConfigurationProperties(LlmProperties.class)
public class LucianoApplication {

	public static void main(String[] args) {
		SpringApplication.run(LucianoApplication.class, args);
	}

}
